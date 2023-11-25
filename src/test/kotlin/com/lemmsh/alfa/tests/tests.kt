package com.lemmsh.alfa.tests

import com.lemmsh.alfa.*
import java.lang.IllegalStateException
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductResolver {
    val products: Map<String, Product> = mapOf(
        "paper" to Product("paper", false, 100, false),
        "Product2" to Product("Product2", true, 50, true),
        "digital" to Product("digital", false, 200, false),
        "corporateKit" to Product("corporateKit", true, 20, true),
        "officeSupplies" to Product("officeSupplies", false, 150, false)
    )
    fun resolve(p: Product): Product {
        return products[p.productType] ?: p
    }
}

class ClientAccountResolver {
    val clientAccounts: Map<String, ClientAccount> = mapOf(
        "BIGCORP" to ClientAccount("BIGCORP", "Big Inc", true, "corporate", 800, false),
        "Client2" to ClientAccount("Client2", "Client2", false, "standard", 600, true),
        "SMALLBIZ" to ClientAccount("SMALLBIZ", "Small Business", false, "standard", 650, false),
        "MIDCORP" to ClientAccount("MIDCORP", "Medium Corp", true, "corporate", 700, false),
        "STARTUP" to ClientAccount("STARTUP", "Startup Inc", false, "standard", 550, true)
    )
    fun resolve(c: ClientAccount): ClientAccount {
        return clientAccounts[c.id]?:c
    }
}

class EmployeeResolver {
    val employees: Map<String, Employee> = mapOf(
        "rockstar.salesperson@example.com" to Employee("rockstar.salesperson@example.com", "CorpSales"),
        "Employee2" to Employee("Employee2", "Division2"),
        "junior.sales@example.com" to Employee("junior.sales@example.com", "RetailSales"),
        "senior.manager@example.com" to Employee("senior.manager@example.com", "Management"),
        "tech.support@example.com" to Employee("tech.support@example.com", "TechSupport")
    )
    fun resolve(e: Employee): Employee {
        return employees[e.email]?:e
    }
}

val defaultResolverContext: ResolverContext = object: ResolverContext{

    val productResolver = ProductResolver()
    val employeeResolver = EmployeeResolver()
    val clientAccountResolver = ClientAccountResolver()

    @Suppress("UNCHECKED_CAST")
    override fun <T> resolve(t: T): T {
        return when (t) {
            is ProductEligibility -> t.copy(product = productResolver.resolve(t.product!!),
                customer = clientAccountResolver.resolve(t.customer!!),
                salesPerson = employeeResolver.resolve(t.salesPerson)) as T
            is Product -> productResolver.resolve(t) as T
            is Employee -> employeeResolver.resolve(t) as T
            is ClientAccount -> clientAccountResolver.resolve(t) as T
            else -> t
        }
    }
}

//here we work with data and can only resolve the employee. The product and the client are stamped on the data in the database
//and we have to return the database the filtering condition instead of the answer
val reversalResolverContext: ResolverContext = object: ResolverContext{
    val employeeResolver = EmployeeResolver()
    @Suppress("UNCHECKED_CAST")
    override fun <T> resolve(t: T): T {
        return when (t) {
            is ProductEligibility -> t.copy(salesPerson = employeeResolver.resolve(t.salesPerson)) as T
            is Employee -> employeeResolver.resolve(t) as T
            else -> t
        }
    }
}



class PolicyDSLTests {
    @Test
    fun `product eligibility policy should return permit on already resolved argument without resolver context`() {
        val req = ProductEligibility(
            ClientAccount(
                "BIGCORP", "Big Inc", true, "corporate", 800, false
            ),
            Product(
                "paper", false, 100, false
            ),
            Employee(
                "rockstar.salesperson@example.com", "CorpSales"
            )
        )
        val evaluation = productSaleEligibilityPolicy.evaluate(req, null)
        val explanation = productSaleEligibilityPolicy.explain(req, null)
        printExplain(explanation)
        assertNotNull(evaluation)
        assertTrue(evaluation is Permit, "Expected the policy to permit.")
    }

    @Test
    fun `product eligibility policy should return reversal condition when product and client are not known upfront`() {
        val req = ProductEligibility(null, null,
            Employee(
                "rockstar.salesperson@example.com", "CorpSales"
            )
        )
        val evaluation = productSaleEligibilityPolicy.evaluateReversal(req, reversalResolverContext)
        val evaluation2 = retailPolicy.evaluateReversal(null, reversalResolverContext)
//        val explanation = productSaleEligibilityPolicy.explain(req, null)
//        printExplain(explanation)
//        assertNotNull(evaluation)
//        assertTrue(evaluation is Permit, "Expected the policy to permit.")
        println(evaluation.conditionForPermit().simplify())
    }


    @Test
    fun `policy on unresolved argument should permit`() {
        val reqUnresolved = ProductEligibility(
            ClientAccount("BIGCORP"),
            Product("paper"),
            Employee("rockstar.salesperson@example.com")
        )
        val evaluationUnresolved = productSaleEligibilityPolicy.evaluate(reqUnresolved, defaultResolverContext)
        val explanationUnresolved = productSaleEligibilityPolicy.explain(reqUnresolved, defaultResolverContext)
        printExplain(explanationUnresolved)
        assertNotNull(evaluationUnresolved)
        assertTrue(evaluationUnresolved is Permit, "Expected the policy to permit.")
    }

    @Test
    fun `policy on resolved argument should deny`() {
        val req = ProductEligibility(
            ClientAccount(
                "Client2", "Client2", false, "standard", 600, true
            ),
            Product(
                "Product2", true, 50, true
            ),
            Employee(
                "Employee2", "Division2"
            )
        )
        val evaluation = productSaleEligibilityPolicy.evaluate(req, defaultResolverContext)
        val explanation = productSaleEligibilityPolicy.explain(req, defaultResolverContext)
        printExplain(explanation)
        assertNotNull(evaluation)
        assertTrue(evaluation is Deny, "Expected the policy to deny.")
    }

    @Test
    fun `policy on unresolved argument should deny`() {
        val reqUnresolved = ProductEligibility(
            ClientAccount("Client2"),
            Product("Product2"),
            Employee("Employee2")
        )
        val evaluationUnresolved = productSaleEligibilityPolicy.evaluate(reqUnresolved, defaultResolverContext)
        val explanationUnresolved = productSaleEligibilityPolicy.explain(reqUnresolved, defaultResolverContext)
        printExplain(explanationUnresolved)
        assertNotNull(evaluationUnresolved)
        assertTrue(evaluationUnresolved is Deny, "Expected the policy to deny.")
    }
    @Test
    fun `policy on digital product with small business client should permit`() {
        val req = ProductEligibility(
            ClientAccount(
                "SMALLBIZ", "Small Business", false, "standard", 650, false
            ),
            Product(
                "digital", false, 200, false
            ),
            Employee(
                "junior.sales@example.com", "RetailSales"
            )
        )
        val evaluation = productSaleEligibilityPolicy.evaluate(req, defaultResolverContext)
        val explanation = productSaleEligibilityPolicy.explain(req, defaultResolverContext)
        printExplain(explanation)
        assertNotNull(evaluation)
        assertTrue(evaluation is Permit, "Expected the policy to permit digital products for small business clients.")
    }

    @Test
    fun `policy on corporate kit with medium corp client should permit`() {
        val req = ProductEligibility(
            ClientAccount(
                "MIDCORP", "Medium Corp", true, "corporate", 700, false
            ),
            Product(
                "corporateKit", true, 20, true
            ),
            Employee(
                "senior.manager@example.com", "Management"
            )
        )
        val evaluation = productSaleEligibilityPolicy.evaluate(req, defaultResolverContext)
        val explanation = productSaleEligibilityPolicy.explain(req, defaultResolverContext)
        printExplain(explanation)
        assertNotNull(evaluation)
        assertTrue(evaluation is Deny, "Expected the policy to deny corporate kits for medium corp clients.")
    }

    @Test
    fun `policy on office supplies with startup client should permit`() {
        val req = ProductEligibility(
            ClientAccount(
                "STARTUP", "Startup Inc", false, "standard", 550, true
            ),
            Product(
                "officeSupplies", false, 150, false
            ),
            Employee(
                "tech.support@example.com", "TechSupport"
            )
        )
        val evaluation = productSaleEligibilityPolicy.evaluate(req, defaultResolverContext)
        val explanation = productSaleEligibilityPolicy.explain(req, defaultResolverContext)
        printExplain(explanation)
        assertNotNull(evaluation)
        assertTrue(evaluation is Permit, "Expected the policy to permit office supplies for startup clients.")
    }
    @Test
    fun `special product policy should permit for in-stock product`() {
        val req = Product("special", true, 100, false)
        val evaluation = specialProductPolicy.evaluate(req, defaultResolverContext)
        val explanation = specialProductPolicy.explain(req, defaultResolverContext)
        printExplain(explanation)
        assertNotNull(evaluation)
        assertTrue(evaluation is Permit, "Expected the policy to permit in-stock special products.")
    }

    @Test
    fun `special product policy should deny for out-of-stock product`() {
        val req = Product("special", true, 0, false)
        val evaluation = specialProductPolicy.evaluate(req, defaultResolverContext)
        val explanation = specialProductPolicy.explain(req, defaultResolverContext)
        printExplain(explanation)
        assertNotNull(evaluation)
        assertTrue(evaluation is Deny, "Expected the policy to deny out-of-stock special products.")
    }

    @Test
    fun `special product policy should deny for restricted product`() {
        val req = Product("special", true, 100, true)
        val evaluation = specialProductPolicy.evaluate(req, defaultResolverContext)
        val explanation = specialProductPolicy.explain(req, defaultResolverContext)
        printExplain(explanation)
        assertNotNull(evaluation)
        assertTrue(evaluation is Deny, "Expected the policy to deny restricted special products.")
    }

    @Test
    fun `premium client policy should permit for client with good credit`() {
        val req = ClientAccount("PREMIUM", "Premium Corp", true, "premium", 750, false)
        val evaluation = premiumClientPolicy.evaluate(req, defaultResolverContext)
        val explanation = premiumClientPolicy.explain(req, defaultResolverContext)
        printExplain(explanation)
        assertNotNull(evaluation)
        assertTrue(evaluation is Permit, "Expected the policy to permit premium clients with good credit.")
    }

    @Test
    fun `premium client policy should deny for client under investigation`() {
        val req = ClientAccount("PREMIUM", "Premium Corp", true, "premium", 600, true)
        val evaluation = premiumClientPolicy.evaluate(req, defaultResolverContext)
        val explanation = premiumClientPolicy.explain(req, defaultResolverContext)
        printExplain(explanation)
        assertNotNull(evaluation)
        assertTrue(evaluation is Deny, "Expected the policy to deny premium clients under investigation.")
    }
}
