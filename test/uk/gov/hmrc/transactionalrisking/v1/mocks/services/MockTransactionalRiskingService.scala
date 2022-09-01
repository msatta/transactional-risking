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

package uk.gov.hmrc.transactionalrisking.v1.mocks.services

import org.scalamock.handlers.CallHandler
import org.scalamock.scalatest.MockFactory
import uk.gov.hmrc.auth.core.authorise.Predicate
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transactionalrisking.controllers.UserRequest
import uk.gov.hmrc.transactionalrisking.models.auth.{AuthOutcome, UserDetails}
import uk.gov.hmrc.transactionalrisking.models.domain.{AssessmentReport, AssessmentRequestForSelfAssessment, Link, Origin, Risk}
import uk.gov.hmrc.transactionalrisking.services.{EnrolmentsAuthService, TransactionalRiskingService}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait MockTransactionalRiskingService extends MockFactory {

  val mockTransactionalRiskingService: TransactionalRiskingService = mock[TransactionalRiskingService]

  object TransactionalRiskingService {

    def assess(request: AssessmentRequestForSelfAssessment, origin: Origin)(implicit hc: HeaderCarrier,
                                                                            ec: ExecutionContext,
                                                                            //                                                                         logContext: EndpointLogContext,
                                                                            userRequest: UserRequest[_],
                                                                            correlationId: String): CallHandler[Future[AssessmentReport]] = {

            (mockTransactionalRiskingService.assess( _: AssessmentRequestForSelfAssessment, _: Origin )(_: HeaderCarrier, _: ExecutionContext, _: UserRequest[_], _:String ))
              .expects(*, *, *, *, *, *)
              .returns(
                Future.successful(
                  AssessmentReport(reportId = new UUID(0,1)
                  , risks = Seq( Risk(title="",body="", action=""
                  , links = Seq(Link(title="", url="")),path="") )
                  , nino="0"
                  , taxYear="2021"
                  , calculationId = new UUID(0,2)) ))
    }
  }
}
