package io.tolgee.service.machineTranslation

import io.tolgee.component.machineTranslation.MtServiceManager
import io.tolgee.component.machineTranslation.TranslateResult
import io.tolgee.component.machineTranslation.metadata.ExampleItem
import io.tolgee.component.machineTranslation.metadata.Metadata
import io.tolgee.configuration.tolgee.TolgeeProperties
import io.tolgee.constants.Message
import io.tolgee.constants.MtServiceType
import io.tolgee.dtos.cacheable.LanguageDto
import io.tolgee.dtos.cacheable.ProjectDto
import io.tolgee.events.OnAfterMachineTranslationEvent
import io.tolgee.events.OnBeforeMachineTranslationEvent
import io.tolgee.exceptions.BadRequestException
import io.tolgee.helpers.TextHelper
import io.tolgee.model.key.Key
import io.tolgee.service.LanguageService
import io.tolgee.service.bigMeta.BigMetaService
import io.tolgee.service.translation.TranslationService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MtService(
  private val translationService: TranslationService,
  private val machineTranslationManager: MtServiceManager,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val mtServiceConfigService: MtServiceConfigService,
  private val tolgeeProperties: TolgeeProperties,
  private val bigMetaService: BigMetaService,
  private val languageService: LanguageService,
) {
  @Transactional
  fun getMachineTranslations(
    project: ProjectDto,
    key: Key?,
    baseTranslationText: String?,
    targetLanguage: LanguageDto,
    services: Set<MtServiceType>?,
  ): Map<MtServiceType, TranslateResult> {
    val baseLanguage =
      languageService.getProjectBaseLanguage(project.id) ?: throw BadRequestException(Message.BASE_LANGUAGE_NOT_FOUND)

    val baseTranslationTextSafe = getBaseTranslation(baseTranslationText, key, baseLanguage)

    return getMachineTranslations(
      project = project,
      baseTranslationText = baseTranslationTextSafe,
      key = key,
      baseLanguage = baseLanguage,
      targetLanguage = targetLanguage,
      services = services,
    )
  }

  fun getBaseTranslation(
    baseTranslationText: String?,
    key: Key?,
    baseLanguage: LanguageDto,
  ): String? {
    val baseTranslationTextSafe =
      baseTranslationText ?: key?.let {
        translationService.find(it, baseLanguage).orElse(null)?.text
      }
    return baseTranslationTextSafe
  }

  fun getPrimaryMachineTranslations(
    projectDto: ProjectDto,
    key: Key,
    targetLanguages: List<LanguageDto>,
    isBatch: Boolean,
  ): List<TranslateResult?> {
    val baseLanguage =
      languageService.getProjectBaseLanguage(projectDto.id)
        ?: throw BadRequestException(Message.BASE_LANGUAGE_NOT_FOUND)

    val baseTranslationText =
      translationService.find(key, baseLanguage).orElse(null)?.text
        ?: return targetLanguages.map { null }

    return getPrimaryMachineTranslations(
      projectDto,
      baseTranslationText,
      key,
      baseLanguage,
      targetLanguages,
      isBatch,
    )
  }

  private fun getPrimaryMachineTranslations(
    project: ProjectDto,
    baseTranslationText: String,
    key: Key?,
    baseLanguage: LanguageDto,
    targetLanguages: List<LanguageDto>,
    isBatch: Boolean,
  ): List<TranslateResult?> {
    publishBeforeEvent(project)

    checkTextLength(baseTranslationText)

    // filter only translations that are not disabled
    val targetLanguageIds = targetLanguages.map { it.id }

    val primaryServices = mtServiceConfigService.getPrimaryServices(targetLanguageIds, project.id)
    val prepared = TextHelper.replaceIcuParams(baseTranslationText)

    val serviceIndexedLanguagesMap =
      targetLanguages
        .asSequence()
        .mapIndexed { idx, lang -> idx to lang }
        .groupBy { primaryServices[it.second.id] }

    val keyName = key?.name

    val metadata =
      getMetadata(
        sourceLanguage = baseLanguage,
        targetLanguages = targetLanguages.filter { primaryServices[it.id]?.serviceType?.usesMetadata == true },
        text = baseTranslationText,
        key = key,
        needsMetadata = true,
        projectDto = project,
      )

    val translationResults =
      serviceIndexedLanguagesMap
        .map { (service, languageIdxPairs) ->
          service?.let {
            val translateResults =
              machineTranslationManager.translate(
                prepared.text,
                baseTranslationText,
                keyName,
                baseLanguage.tag,
                languageIdxPairs.map { it.second.tag },
                service,
                metadata = metadata,
                isBatch = isBatch,
              )

            val withReplacedParams =
              translateResults.map { translateResult ->
                translateResult.translatedText = translateResult.translatedText?.replaceParams(prepared.params)
                translateResult
              }
            languageIdxPairs.map { it.first }.zip(withReplacedParams)
          } ?: languageIdxPairs.map { it.first to null }
        }
        .flatten()
        .sortedBy { it.first }
        .map { it.second }

    val actualPrice = translationResults.sumOf { it?.actualPrice ?: 0 }

    publishAfterEvent(project, actualPrice)

    return translationResults
  }

  fun getMachineTranslations(
    project: ProjectDto,
    baseTranslationText: String?,
    key: Key?,
    baseLanguage: LanguageDto,
    targetLanguage: LanguageDto,
    services: Set<MtServiceType>?,
  ): Map<MtServiceType, TranslateResult> {
    checkTextLength(baseTranslationText)
    val servicesToUse = getServicesToUse(targetLanguage, services)

    if (baseTranslationText.isNullOrBlank()) {
      return getEmptyResults(servicesToUse)
    }

    publishBeforeEvent(project)

    val prepared = TextHelper.replaceIcuParams(baseTranslationText)

    val anyNeedsMetadata = servicesToUse.any { it.serviceType.usesMetadata }

    val metadata =
      getMetadata(baseLanguage, targetLanguage, baseTranslationText, key, anyNeedsMetadata, project)

    val keyName = key?.name

    val results =
      machineTranslationManager
        .translate(
          text = prepared.text,
          textRaw = baseTranslationText,
          keyName = keyName,
          sourceLanguageTag = baseLanguage.tag,
          targetLanguageTag = targetLanguage.tag,
          serviceInfos = servicesToUse,
          metadata = metadata,
          isBatch = false,
        )

    val actualPrice = results.entries.sumOf { it.value.actualPrice }

    publishAfterEvent(project, actualPrice)

    return results.map { (serviceInfo, translated) ->
      translated.translatedText = translated.translatedText?.replaceParams(prepared.params)
      serviceInfo.serviceType to translated
    }.toMap()
  }

  private fun getEmptyResults(servicesToUse: Set<MtServiceInfo>): Map<MtServiceType, TranslateResult> {
    return servicesToUse.associate {
      it.serviceType to
        TranslateResult(
          translatedText = null,
          contextDescription = null,
          actualPrice = 0,
          usedService = it.serviceType,
          baseBlank = true,
        )
    }
  }

  fun getServicesToUse(
    targetLanguage: LanguageDto,
    desiredServices: Set<MtServiceType>?,
  ): Set<MtServiceInfo> {
    val enabledServices = mtServiceConfigService.getEnabledServiceInfos(targetLanguage)
    checkServices(desired = desiredServices?.toSet(), enabled = enabledServices.map { it.serviceType })
    return enabledServices.filter { desiredServices?.contains(it.serviceType) ?: true }
      .toSet()
  }

  private fun checkServices(
    desired: Set<MtServiceType>?,
    enabled: List<MtServiceType>,
  ) {
    if (desired != null && desired.any { !enabled.contains(it) }) {
      throw BadRequestException(Message.MT_SERVICE_NOT_ENABLED)
    }
  }

  private fun publishAfterEvent(
    project: ProjectDto,
    actualPrice: Int,
  ) {
    applicationEventPublisher.publishEvent(
      OnAfterMachineTranslationEvent(this, project.organizationOwnerId, actualPrice),
    )
  }

  private fun publishBeforeEvent(project: ProjectDto) {
    applicationEventPublisher.publishEvent(
      OnBeforeMachineTranslationEvent(this, project.organizationOwnerId),
    )
  }

  private fun checkTextLength(text: String?) {
    text ?: return
    if (text.length > tolgeeProperties.maxTranslationTextLength) {
      throw BadRequestException(Message.TRANSLATION_TEXT_TOO_LONG)
    }
  }

  private fun String.replaceParams(params: Map<String, String>): String {
    var replaced = this
    params.forEach { (placeholder, text) ->
      replaced = replaced.replace(placeholder, text)
    }
    return replaced
  }

  private fun getExamples(
    targetLanguage: LanguageDto,
    text: String,
    keyId: Long?,
  ): List<ExampleItem> {
    return translationService.getTranslationMemorySuggestions(
      sourceTranslationText = text,
      key = null,
      targetLanguage = targetLanguage,
      pageable = PageRequest.of(0, 5),
    ).content
      .filter { it.keyId != keyId }
      .map {
        ExampleItem(key = it.keyName, source = it.baseTranslationText, target = it.targetTranslationText)
      }
  }

  private fun getCloseItems(
    sourceLanguage: LanguageDto,
    targetLanguage: LanguageDto,
    closeKeyIds: List<Long>,
    keyId: Long?,
  ): List<ExampleItem> {
    val translations =
      this.translationService.findAllByKeyIdsAndLanguageIds(
        closeKeyIds,
        languageIds = listOf(sourceLanguage.id, targetLanguage.id),
      )

    val sourceTranslations = translations.filter { it.language.id == sourceLanguage.id }

    val targetTranslations = translations.filter { it.language.id == targetLanguage.id }

    return sourceTranslations
      .filter { !it.text.isNullOrEmpty() }
      .map {
        ExampleItem(
          key = it.key.name,
          source = it.text ?: "",
          target =
            if (it.key.id != keyId) {
              targetTranslations.find { target -> target.key.id == it.key.id }?.text ?: ""
            } else {
              ""
            },
        )
      }
  }

  private fun getMetadata(
    sourceLanguage: LanguageDto,
    targetLanguages: List<LanguageDto>,
    text: String,
    key: Key?,
    needsMetadata: Boolean,
    projectDto: ProjectDto,
  ): Map<String, Metadata>? {
    if (!needsMetadata) {
      return null
    }

    val closeKeyIds = key?.let { bigMetaService.getCloseKeyIds(it.id) }
    val keyDescription = key?.keyMeta?.description

    return targetLanguages.associate { targetLanguage ->
      targetLanguage.tag to
        Metadata(
          examples = getExamples(targetLanguage, text, key?.id),
          closeItems = closeKeyIds?.let { getCloseItems(sourceLanguage, targetLanguage, it, key.id) } ?: listOf(),
          keyDescription = keyDescription,
          projectDescription = projectDto.aiTranslatorPromptDescription,
          languageDescription = targetLanguage.aiTranslatorPromptDescription,
        )
    }
  }

  private fun getMetadata(
    sourceLanguage: LanguageDto,
    targetLanguages: LanguageDto,
    text: String,
    key: Key?,
    needsMetadata: Boolean = true,
    projectDto: ProjectDto,
  ): Metadata? {
    return getMetadata(sourceLanguage, listOf(targetLanguages), text, key, needsMetadata, projectDto)?.get(
      targetLanguages.tag,
    )
  }
}
