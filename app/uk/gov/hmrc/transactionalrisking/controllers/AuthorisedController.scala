/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.transactionalrisking.controllers

import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.auth.core.authorise.Predicate
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transactionalrisking.model.{DownstreamError, ForbiddenDownstreamError, LegacyUnauthorisedError, VrnFormatError}
import uk.gov.hmrc.transactionalrisking.model.auth.UserDetails
import uk.gov.hmrc.transactionalrisking.services.EnrolmentsAuthService

import scala.concurrent.{ExecutionContext, Future}

case class UserRequest[A](userDetails: UserDetails, request: Request[A]) extends WrappedRequest[A](request)

abstract class AuthorisedController(cc: ControllerComponents)(implicit ec: ExecutionContext) extends BackendController(cc) {

  val authService: EnrolmentsAuthService

  def authorisedAction(vrn: String, nrsRequired: Boolean = false): ActionBuilder[UserRequest, AnyContent] = new ActionBuilder[UserRequest, AnyContent] {

    override def parser: BodyParser[AnyContent] = cc.parsers.defaultBodyParser

    override protected def executionContext: ExecutionContext = cc.executionContext

    //TODO fix predicate
    def predicate(vrn: String): Predicate =
      Enrolment("HMRC-MTD-VAT")
        .withIdentifier("VRN", vrn)
        .withDelegatedAuthRule("mtd-vat-auth")

    override def invokeBlock[A](request: Request[A], block: UserRequest[A] => Future[Result]): Future[Result] = {

      implicit val headerCarrier: HeaderCarrier = hc(request)

      val clientId = request.headers.get("X-Client-Id").getOrElse("N/A")

      if (VrnValidation.validate(vrn) == Nil) {
        authService.authorised(predicate(vrn), nrsRequired).flatMap[Result] {
          case Right(userDetails) => block(UserRequest(userDetails.copy(clientId = clientId), request))
          case Left(LegacyUnauthorisedError) => Future.successful(Forbidden(Json.toJson(LegacyUnauthorisedError)))
          case Left(ForbiddenDownstreamError) => Future.successful(Forbidden(Json.toJson(DownstreamError)))
          case Left(_) => Future.successful(InternalServerError(Json.toJson(DownstreamError)))
        }
      } else {
        Future.successful(BadRequest(Json.toJson(VrnFormatError)))
      }
    }
  }
}
