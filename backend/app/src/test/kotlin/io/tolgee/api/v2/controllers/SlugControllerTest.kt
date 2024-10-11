package io.tolgee.api.v2.controllers

import io.tolgee.CleanDbBeforeClass
import io.tolgee.CleanDbBeforeMethod
import io.tolgee.dtos.request.GenerateSlugDto
import io.tolgee.fixtures.andAssertThatJson
import io.tolgee.fixtures.andIsOk
import io.tolgee.model.Organization
import io.tolgee.model.Project
import io.tolgee.testing.AuthorizedControllerTest
import org.junit.jupiter.api.Test

@CleanDbBeforeClass
class SlugControllerTest : AuthorizedControllerTest() {
  @Test
  fun testValidateOrganizationSlug() {
    performAuthGet("/v2/slug/validate-organization/hello-1").andIsOk.andAssertThatJson {
      isEqualTo(true)
    }
    organizationRepository.save(
      Organization(
        name = "aaa",
        slug = "hello-1",
      ),
    )
    performAuthGet("/v2/slug/validate-organization/hello-1").andIsOk.andAssertThatJson {
      isEqualTo(false)
    }
  }

  @Test
  fun testValidateRepositorySlug() {
    performAuthGet("/v2/slug/validate-project/hello-1").andIsOk.andAssertThatJson {
      isEqualTo(true)
    }
    projectRepository.save(
      Project(
        name = "aaa",
        slug = "hello-1",
      ).also { it.organizationOwner = dbPopulator.createBase("proj").organization },
    )
    performAuthGet("/v2/slug/validate-project/hello-1").andIsOk.andAssertThatJson {
      isEqualTo(false)
    }
  }

  @Test
  @CleanDbBeforeMethod
  fun testGenerateOrganizationSlug() {
    performAuthPost("/v2/slug/generate-organization", GenerateSlugDto("Hello world"))
      .andIsOk.andAssertThatJson {
        isEqualTo("hello-world")
      }

    organizationRepository.save(
      Organization(
        name = "aaa",
        slug = "hello-world",
      ),
    )

    performAuthPost("/v2/slug/generate-organization", GenerateSlugDto("Hello world"))
      .andIsOk.andAssertThatJson {
        isEqualTo("hello-world1")
      }
  }

  @Test
  fun testGenerateOrganizationSlugSameOld() {
    organizationRepository.save(
      Organization(
        name = "aaa",
        slug = "hello-world",
      ),
    )

    performAuthPost("/v2/slug/generate-organization", GenerateSlugDto("Hello world", "hello-world"))
      .andIsOk.andAssertThatJson {
        isEqualTo("hello-world")
      }
  }

  @Test
  fun testGenerateRepositorySlug() {
    projectRepository.save(
      Project(
        name = "aaa",
        slug = "hello-world",
      ).also { it.organizationOwner = dbPopulator.createBase("proj").organization },
    )
    performAuthPost("/v2/slug/generate-project", GenerateSlugDto("Hello world"))
      .andIsOk.andAssertThatJson {
        isEqualTo("hello-world1")
      }
  }
}
