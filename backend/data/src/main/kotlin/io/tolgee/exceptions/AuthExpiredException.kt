package io.tolgee.exceptions

import io.tolgee.constants.Message
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.UNAUTHORIZED) // TODO: there doesn't seem to be a specific status for this
class AuthExpiredException(message: Message) : ErrorException(message) {
  override val httpStatus: HttpStatus
    get() = HttpStatus.UNAUTHORIZED
}