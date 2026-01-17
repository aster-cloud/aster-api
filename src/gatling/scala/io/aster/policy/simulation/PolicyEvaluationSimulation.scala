package io.aster.policy.simulation

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration._

class PolicyEvaluationSimulation extends Simulation {

  private val baseUrl = sys.env.getOrElse("POLICY_API_BASE_URL", "http://localhost:8080")
  private val tenant = sys.env.getOrElse("POLICY_TENANT", "default")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private case class PolicyRequest(body: String, name: String)

  private def randomTraceId(): String = UUID.randomUUID().toString.replace("-", "").take(32)

  private def randomSpanId(): String = UUID.randomUUID().toString.replace("-", "").take(16)

  // 为 traceparent 注入生成唯一 TraceId/SpanId
  private val traceContextFeeder = Iterator.continually {
    val traceId = randomTraceId()
    val spanId = randomSpanId()
    Map(
      "traceId" -> traceId,
      "spanId" -> spanId,
      "traceparent" -> s"00-$traceId-$spanId-01"
    )
  }

  private val loanRestPayload =
    """{
       |  "policyModule": "aster.finance.loan",
       |  "policyFunction": "evaluateLoanEligibility",
       |  "context": [
       |    {"loanId":"LN-GAT-1001","applicantId":"APP-9001","amountRequested":250000,"purposeCode":"home","termMonths":72},
       |    {"applicantId":"APP-9001","age":37,"annualIncome":520000,"creditScore":760,"existingDebtMonthly":2500,"yearsEmployed":7}
       |  ]
       |}
       |""".stripMargin.replaceAll("\s+", "")

  private val creditRestPayload =
    """{
       |  "policyModule": "aster.finance.creditcard",
       |  "policyFunction": "evaluateCreditCardApplication",
       |  "context": [
       |    {"applicantId":"CARD-777","age":34,"annualIncome":180000,"creditScore":720,"existingCreditCards":2,"monthlyRent":2500,"employmentStatus":"Full-time","yearsAtCurrentJob":5},
       |    {"bankruptcyCount":0,"latePayments":1,"utilization":32,"accountAge":96,"hardInquiries":2},
       |    {"productType":"Premium","requestedLimit":20000,"hasRewards":true,"annualFee":199}
       |  ]
       |}
       |""".stripMargin.replaceAll("\s+", "")

  private val fraudRestPayload =
    """{
       |  "policyModule": "aster.finance.fraud",
       |  "policyFunction": "detectFraud",
       |  "context": [
       |    {"transactionId":"TX-555","accountId":"ACCT-4401","amount":95000,"timestamp":1700000100},
       |    {"accountId":"ACCT-4401","averageAmount":25000,"suspiciousCount":1,"accountAge":240,"lastTimestamp":1699999500}
       |  ]
       |}
       |""".stripMargin.replaceAll("\s+", "")

  private val graphQlLoanPayload =
    """{
       |  "query": "query Loan($application: LoanApplicationInfoInput!, $applicant: LoanApplicantProfileInput!) { evaluateLoanEligibility(application: $application, applicant: $applicant) { approved reason maxApprovedAmount interestRateBps termMonths } }",
       |  "variables": {
       |    "application": {"loanId":"GL-2001","applicantId":"APP-QL-1","amountRequested":180000,"purposeCode":"home","termMonths":60},
       |    "applicant": {"applicantId":"APP-QL-1","age":33,"annualIncome":210000,"creditScore":745,"existingDebtMonthly":1200,"yearsEmployed":6}
       |  }
       |}
       |""".stripMargin.replaceAll("\s+", "")

  private val policies = Array(
    PolicyRequest(loanRestPayload, "loan"),
    PolicyRequest(creditRestPayload, "creditcard"),
    PolicyRequest(fraudRestPayload, "fraud")
  )

  private def selectPolicy(): PolicyRequest = {
    val r = ThreadLocalRandom.current().nextDouble(1.0)
    if (r < 0.7) policies(0)
    else if (r < 0.9) policies(1)
    else policies(2)
  }

  private val restFeeder = Iterator.continually {
    val request = selectPolicy()
    Map("requestBody" -> request.body, "policyName" -> request.name)
  }

  private val graphQlFeeder = Iterator.continually {
    Map("graphqlBody" -> graphQlLoanPayload)
  }

  private val restScenario = scenario("REST Policy Evaluation")
    .feed(traceContextFeeder)
    .feed(restFeeder)
    .exec(
      http("REST_${policyName}")
        .post("/api/policies/evaluate")
        .header("X-Tenant-Id", tenant)
        .header("traceparent", "${traceparent}")
        .body(StringBody("${requestBody}"))
        .check(status.is(200))
    )

  private val graphQlScenario = scenario("GraphQL Loan Evaluation")
    .feed(traceContextFeeder)
    .feed(graphQlFeeder)
    .exec(
      http("GraphQL_Loan")
        .post("/graphql")
        .header("traceparent", "${traceparent}")
        .body(StringBody("${graphqlBody}"))
        .check(status.is(200))
    )

  private val steadyLoad = restScenario.inject(constantUsersPerSec(1000).during(10.minutes))
  private val rampLoad = restScenario.inject(rampUsersPerSec(0).to(2000).during(5.minutes))
  private val spikeLoad = restScenario.inject(rampUsersPerSec(1000).to(5000).during(1.minutes))
  private val graphQlLoad = graphQlScenario.inject(constantUsersPerSec(100).during(5.minutes))

  setUp(steadyLoad, rampLoad, spikeLoad, graphQlLoad)
    .protocols(httpProtocol)
    .assertions(
      global.failedRequests.percent.lte(2),
      details("REST_loan").responseTime.percentile3.lt(20),
      details("REST_creditcard").responseTime.percentile3.lt(20),
      details("REST_fraud").responseTime.percentile3.lt(20),
      details("GraphQL_Loan").responseTime.percentile3.lt(20)
    )
}
