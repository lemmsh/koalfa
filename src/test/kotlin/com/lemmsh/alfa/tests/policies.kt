package com.lemmsh.alfa.tests

import com.lemmsh.alfa.*

/**
 * we're covering three cases here
 * 1. direct evaluation: we call the policy with all the attributes resolved and get the reply
 * 2. explanation: we call the policy with all the attributes to explain the result for us
 * 3. reversal: we call the policy with some of the attributes unknown so it returns us the minimal condition to be met for it to return 'Permit'
 *
 * the case 3 is non-trivial, as we either need to adjust the resolvers logic to return partially resolved attributes,
 *  or we need to adjust the DSL and introduce some kind of 'resolver context' to the engine, with the type of the object
 *  as the key for the resolvers, and provide this context into the evaluate function
 *  We take the second approach
 */

data class Product(val productType: String, val corporateOnly: Boolean? = null, val quantityAvailable: Int? = null, val isRestricted: Boolean? = null)
data class ClientAccount(val id: String, val name: String? = null, val isCorporate: Boolean? = null, val accountType: String? = null, val creditRating: Int? = null, val isUnderInvestigation: Boolean? = null)
data class Employee(val email: String, val division: String? = null)


val retailPolicy = policy<Product> {
    hint("retail products are always allowed for order")
    applicableWhen({
        it.corporateOnly == false
    }, reversalCondition = { data, resolvers -> p("product.scope") is_not_in("Corporate") })
    permit()
}.build()

data class CorpSalesPolicyArg(val employee: Employee, val product: Product?)
val corporateSalesPolicy = policy<CorpSalesPolicyArg> {
    hint("only sales from the Corporate Sales are allowed to sell corporate-only products")
    applicableWhen({ it.product!!.corporateOnly == true }, reversalCondition = {data, resolvers -> p("product.scope") is_in ("Corporate")})
    condition({
        it.employee.division == "CorpSales" && it.product!!.corporateOnly == true
    }, reversalCondition = {data, resolvers -> (p("employee.division") is_in ("CorpSales")) and (p("product.scope") is_in ("Corporate"))})
}.build()

data class CorpSaleCoveragePolicyArgs(val employee: Employee, val customer: ClientAccount?)
val corpCoveragePolicy = policy<CorpSaleCoveragePolicyArgs> {
    hint("only corp sales can cover corp sales process")
    applicableWhen({ it.customer!!.isCorporate!! }, reversalCondition = {data, resolvers -> p("client.type") eq "corporate"})
    condition ({ it.employee.division == "CorpSales" }, "the employee belongs to the corp sales dept")
}.build()


data class ProductEligibility(val customer: ClientAccount?, val product: Product?, val salesPerson: Employee)
val productSaleEligibilityPolicy = policy<ProductEligibility> {
    hint("restricting sales permission")
    applicableWhen { true } //when we call it, we mean it
    firstApplicable()
    policy(retailPolicy) {it.product}
    policy(corpCoveragePolicy) { CorpSaleCoveragePolicyArgs(it.salesPerson, it.customer) }
    policy(corporateSalesPolicy) { CorpSalesPolicyArg(it.salesPerson, it.product) }
}.build()

val specialProductPolicy = policy<Product> {
    hint("special products require additional checks")
    applicableWhen { it.productType == "special" }
    mergePoliciesAs(PermitUnlessDeny)
    condition ({ it.quantityAvailable!! > 0 }, "product must be in stock")
    condition ({ it.isRestricted == false }, "product must not be restricted")
}.build()

val premiumClientPolicy = policy<ClientAccount> {
    hint("premium clients have fewer restrictions")
    applicableWhen { it.accountType == "premium" }
    mergePoliciesAs(DenyUnlessPermit)
    condition ({ it.creditRating!! >= 700 }, "client must have good credit rating")
}.build()



