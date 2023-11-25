package com.lemmsh.alfa


import java.util.*

/**
 * remaining:
 *  carve out the project for open source
 *  at least one test for reversal
 *  cleaup comments
 */

infix fun Condition.and(other: Condition) = Condition.And(this, other)
infix fun Condition.or(other: Condition) = Condition.Or(this, other)
fun not(other: Condition) = Condition.Not(other)

typealias ValueList = SortedSet<String>

data class Parameter(
    val value: String?
) {

    infix fun is_not_in(list: String): Condition {
        return not(is_in(list))
    }

    infix fun is_in(list: String): Condition {
        return is_in(sortedSetOf(list))
    }


    infix fun is_in(list: List<String>): Condition {
        return is_in(list.toSortedSet())
    }

    infix fun is_in(list: ValueList): Condition {
        return Condition.In(this, list)
    }

    infix fun eq(targetValue: String): Condition {
        return Condition.Equals(this, targetValue)
    }

    override fun toString(): String {
        return value?:"unknown parameter"
    }
}

fun p(v: String?): Parameter = Parameter(v)

class PolicyBuilder<T> {
    private var hint: PolicyHint<T> = PolicyHint({""}, "")
    private var applicable: LogicalExpression<T>? = null
    private var mergeStrategy: DecisionMergeStrategy = FirstApplicable
    private var conditions = mutableListOf<PolicyCondition<T>>()
    private val policies = mutableListOf<NestedPolicy<*, T>>()

    fun applicableWhen(expression: (T) -> Boolean) {
        applicable = LogicalExpression<T>(expression, null)
    }
    fun applicableWhen(expression: (T) -> Boolean, reversalCondition: ReversalCondition<T>) {
        applicable = LogicalExpression<T>(expression, reversalCondition)
    }

    private fun conditionHint(comment: String, clarification: (T) -> String): ConditionHint<T> = ConditionHint(clarification, comment)

    fun hint(name: String, clarification: (T) -> String = {""}) {
        this.hint = PolicyHint(clarification, name)
    }

    fun mergePoliciesAs(strategy: DecisionMergeStrategy) {
        mergeStrategy = strategy
    }

    fun firstApplicable() {
        mergeStrategy = FirstApplicable
    }
    fun denyUnlessPermit() {
        mergeStrategy = DenyUnlessPermit
    }
    fun permitUnlessDeny() {
        mergeStrategy = PermitUnlessDeny
    }

    fun deny(): Unit {
        this.conditions.add(PolicyCondition(
            {false}, ConditionHint({""}, "always deny"), {_, _ -> Condition.False}
        ))
    }

    fun permit(): Unit {
        this.conditions.add(PolicyCondition(
            {true}, ConditionHint({""}, "always permit"), {_, _ -> Condition.True}
        ))
    }


    fun condition(expression: (T) -> Boolean, reversalCondition: ReversalCondition<T>? = null) {
        addCondition(expression, ConditionHint({""}, ""), reversalCondition)
    }

    fun condition(expression: (T) -> Boolean) {
        addCondition(expression, ConditionHint({""}, ""), null)
    }

    fun condition(expression: (T) -> Boolean, comment: String, hint: (T) -> String = {""}, reversalCondition: ReversalCondition<T>? = null) {
        addCondition(expression, conditionHint(comment, hint), reversalCondition)
    }


    private fun addCondition(expression: (T) -> Boolean, hint: ConditionHint<T>, reversalCondition: ReversalCondition<T>?) {
        this.conditions.add(PolicyCondition<T>(expression, hint, reversalCondition))
    }


    fun <X> policy(policy: Policy<X>, block: (T) -> X?) {
        val nestedPolicy = NestedPolicy(policy, block)
        this.policies.add(nestedPolicy)
    }

    fun build(): Policy<T> {
        return Policy(
            hint,
            applicable!!,
            mergeStrategy,
            conditions.toList(),
            policies.toList(),
        )
    }

}

fun <T> policy(block: PolicyBuilder<T>.() -> Unit): PolicyBuilder<T> {
    return PolicyBuilder<T>().apply(block)
}













