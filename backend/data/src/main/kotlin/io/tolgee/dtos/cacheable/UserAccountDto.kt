package io.tolgee.dtos.cacheable

import io.tolgee.model.UserAccount
import java.io.Serializable
import java.util.*

data class UserAccountDto(
  val name: String,
  val username: String,
  val role: UserAccount.Role?,
  val id: Long,
  val needsSuperJwt: Boolean,
  val avatarHash: String?,
  val deleted: Boolean,
  val tokensValidNotBefore: Date?,
  val emailVerified: Boolean,
  val thirdPartyAuth: String?,
  val ssoRefreshToken: String?,
  val ssoDomain: String?,
) : Serializable {
  companion object {
    fun fromEntity(entity: UserAccount) =
      UserAccountDto(
        name = entity.name,
        username = entity.username,
        role = entity.role,
        id = entity.id,
        needsSuperJwt = entity.needsSuperJwt,
        avatarHash = entity.avatarHash,
        deleted = entity.deletedAt != null,
        tokensValidNotBefore = entity.tokensValidNotBefore,
        emailVerified = entity.emailVerification == null,
        thirdPartyAuth = entity.thirdPartyAuthType,
        ssoRefreshToken = entity.ssoRefreshToken,
        ssoDomain = entity.ssoConfig?.domainName ?: "",
      )
  }

  override fun toString(): String = username
}
