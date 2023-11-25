package com.lemmsh.alfa

import java.lang.IllegalStateException
import java.lang.RuntimeException
import kotlin.NullPointerException


sealed class PolicyResult
object Permit : PolicyResult() {
    override fun toString(): String {
        return "permit"
    }
}
data class Deny(val message: String) : PolicyResult() {
    override fun toString(): String {
        return "deny ($message)"
    }
}
data class NotApplicable(val message: String) : PolicyResult() {
    override fun toString(): String {
        return "not applicable($message)"
    }
}
data class Undefined(val message: String, val exception: Exception? = null) : PolicyResult() {
    override fun toString(): String {
        return "undefined ($message)"
    }
}

sealed class DecisionMergeStrategy
object FirstApplicable : DecisionMergeStrategy() {
    override fun toString(): String {
        return "first applicable"
    }
}
object DenyUnlessPermit : DecisionMergeStrategy() {
    override fun toString(): String {
        return "deny, unless explicitly permitted"
    }
}
object PermitUnlessDeny : DecisionMergeStrategy() {
    override fun toString(): String {
        return "permit, unless explicitly denied"
    }
}

open class PolicyHint<T>(val clarification: (T) -> String, val name: String) {

    open fun render(data: T?, policyApplicability: Condition?): String {
        return when (data) {
            null -> if (policyApplicability != null )"'$name' [ ${if (policyApplicability is Condition.True) "applicable" else "not applicable"} ]" else name
            else -> {
                val c = clarification(data)
                "'$name' [ ${if (policyApplicability is Condition.True) "applicable" else "not applicable"} ] ${if (c.isNotBlank()) "(${c})" else ""}"
            }
        }
    }
    operator fun invoke(): String = render(null, null)
    operator fun invoke(data: T?): String = render(data, null)
    operator fun invoke(policyApplicability: Condition?): String  = render(null, policyApplicability)
    operator fun invoke(data: T?, policyApplicability: Condition?): String = render(data, policyApplicability)
}
class ConditionHint<T>(clarification: (T) -> String, name: String): PolicyHint<T>(clarification, name) {
    override fun render(data: T?, conditionResult: Condition?): String {
        return when (data) {
            null -> if (conditionResult != null )"'$name' [ ${conditionResult} ]" else name
            else -> {
                val c = clarification(data)
                "'$name' [ ${conditionResult} ] ${if (c.isNotBlank()) "(${c})" else ""}"
            }
        }
    }
}

typealias ReversalCondition<T> = (T?, ResolverContext?) -> Condition
data class PolicyCondition<T>(
    private val expression: (T) -> Boolean,
    val hint: ConditionHint<T>,
    private val reversalCondition: ReversalCondition<T>?
) {
    operator fun invoke(data: T?, policyHint: PolicyHint<T>): Condition {
        return when (data) {
            null -> Condition.Undefined(policyHint() + " [${hint()} - null argument]")
            else -> when (expression.invoke(data)) {
                true -> Condition.True
                else -> Condition.False
            }
        }
    }

    fun reversal(data: T?, policyHint: PolicyHint<T>, resolverContext: ResolverContext?): Condition {
        return reversalCondition?.invoke(data, resolverContext)?:Condition.Undefined(policyHint() + "[" + hint() + " is undefined]")
    }
}
data class LogicalExpression<T>(
    private val expression: (T) -> Boolean,
    private val reversalCondition: ReversalCondition<T>?
) {
    operator fun invoke(data: T?, policyHint: PolicyHint<T>): Condition {
        return when {
            data == null -> Condition.Undefined(policyHint() + " [null argument]");
            else -> when(expression(data)) {
                true -> Condition.True
                else -> Condition.False
            }
        }
    }
    fun reversal(data: T?, policyHint: PolicyHint<T>, resolverContext: ResolverContext?): Condition {
        return reversalCondition?.invoke(data, resolverContext)?:Condition.Undefined(policyHint())
    }
}
data class NestedPolicy<X, T> (
    private val policy: Policy<X>,
    private val projection: ((T) -> X?),
) {
    fun evaluate(data: T, resolverContext: ResolverContext?): PolicyResult {
        val converted = projection.invoke(data)
        return if (converted == null) Undefined("impossible state") else policy.evaluate(converted, resolverContext)
    }
    fun isApplicable(data: T): Condition {
        val converted = projection.invoke(data)
        return if (converted == null) Condition.Undefined(policy.hint()) else policy.isApplicable(converted)
    }
    fun explain(data: T, resolverContext: ResolverContext?, level: Int, acc: MutableList<PolicyExplainRecord>): Unit {
        val v = projection.invoke(data)
        if (v != null) policy.explain(v, resolverContext, level, acc)
    }
    fun evaluateReversal(data: T?, resolverContext: ResolverContext?): ReversalEvaluationResult {
        val converted = data?.let { projection.invoke(it) }
        return policy.evaluateReversal(converted, resolverContext)
    }
}
data class ConditionExplainRecord(
    val conditionHint: String, val conditionResult: Condition
)
data class PolicyExplainRecord(
    val policyHint: String, val level: Int, val result: PolicyResult, val mergeStrategy: DecisionMergeStrategy,
    val conditionHints: List<ConditionExplainRecord>
)
interface ResolverContext {
    fun <T> resolve(t: T): T
}
data class ReversalEvaluationResult(val applicabilityCondition: Condition, val resultingCondition: Condition? = null) {
    //this is the full condition to be satisfied for a 'permit' response. Should, for example, return false when the policy is not applicable
    fun conditionForPermit(): Condition = applicabilityCondition and (resultingCondition?:Condition.False)
}

class Policy<T>(
    val hint: PolicyHint<T>,
    private val applicable: LogicalExpression<T>,
    private val mergeStrategy: DecisionMergeStrategy,
    private val conditions: List<PolicyCondition<T>>,
    private val policies: List<NestedPolicy<*, T>>,
    ) {

    /**
     * this is made in a way when it short-circuits when possible, so the code may look ugly
     */
    fun evaluate(data: T, resolverContext: ResolverContext?): PolicyResult {
        return try {
            val resolvedData = resolverContext?.resolve(data) ?: data
            val applicability = isApplicable(resolvedData)
            when {
                applicability is Condition.False -> return NotApplicable("policy '${hint()}' is not applicable")
                applicability is Condition.Undefined -> return Undefined(applicability.comment)
                applicability !is Condition.True -> return Undefined("policy '${hint()}' applicability condition evaluated to ${applicability} which is strange")
            }
            when (mergeStrategy) {
                is FirstApplicable -> evaluateFirstApplicable(resolvedData, resolverContext)
                is PermitUnlessDeny -> evaluatePermitUnlessDeny(resolvedData, resolverContext)
                is DenyUnlessPermit -> evaluateDenyUnlessPermit(resolvedData, resolverContext)
            }
        } catch (e: Exception) {
            Undefined("Exception during policy evaluation", e)
        }
    }


    private fun evaluateFirstApplicable(data: T, resolverContext: ResolverContext?): PolicyResult {
        for (nestedPolicy in this.policies) {
            val applicability = nestedPolicy.isApplicable(data)
            when (applicability) {
                is Condition.Undefined -> return Undefined(applicability.comment)
                is Condition.False -> continue
                is Condition.True -> return nestedPolicy.evaluate(data, resolverContext)
                else -> return Undefined("${hint()} => unexpected applicability result ${applicability}")
            }
        }
        //here if no nested policy is applicable we return the value of the conditions. Conditions are merged by 'and'.
        // If there's no conditions, we return NotApplicable
        if (conditions.isEmpty()) return NotApplicable("policy '${hint()}' is not applicable")
        for (c in conditions) {
            when (val conditionResult = c(data, hint)) {
                is Condition.Undefined -> return Undefined(conditionResult.comment)
                is Condition.False -> return Deny(hint() + " [ " + c.hint.invoke(data, conditionResult) + " ]")
                is Condition.True -> continue
                else -> return Undefined("${hint()} => unexpected condition ${c.hint()} result ${conditionResult}")
            }
        }
        return Permit
    }

    private fun evaluateDenyUnlessPermit(data: T, resolverContext: ResolverContext?): PolicyResult {
        for (nestedPolicy in this.policies) {
            val applicability = nestedPolicy.isApplicable(data)
            if (applicability == Condition.True) {
                val evaluation = nestedPolicy.evaluate(data, resolverContext)
                when {
                    evaluation is Permit -> return Permit
                    evaluation is Undefined -> return evaluation
                }
            } else if (applicability is Condition.Undefined) {
                return Undefined(applicability.comment)
            }
        }
        //here if no applicable policy triggered Deny yet, we check all the conditions
        // If there's no conditions, we return Deny
        if (conditions.isEmpty()) return Deny("")
        for (c in conditions) {
            when (val conditionResult = c(data, hint)) {
                is Condition.Undefined -> return Undefined(c.hint.invoke(data, conditionResult))
                is Condition.False -> continue
                is Condition.True -> return Permit
                else -> throw IllegalStateException("unexpected condition result ${conditionResult} for ${c.hint()}")
            }
        }
        return Deny(hint(data))
    }

    private fun evaluatePermitUnlessDeny(data: T, resolverContext: ResolverContext?): PolicyResult {
        for (nestedPolicy in this.policies) {
            val applicability = nestedPolicy.isApplicable(data)
            if (applicability == Condition.True) {
                val evaluation = nestedPolicy.evaluate(data, resolverContext)
                when {
                    evaluation is Deny -> return evaluation
                    evaluation is Undefined -> return evaluation
                }
            } else if (applicability is Condition.Undefined) {
                return Undefined(applicability.comment)
            }
        }
        //here if no applicable policy triggered Permit yet, we check all the conditions
        // If there's no conditions, we return Permit
        if (conditions.isEmpty()) return Permit
        for (c in conditions) {
            when (val conditionResult = c(data, hint)) {
                is Condition.Undefined -> return Undefined(conditionResult.comment)
                is Condition.True -> continue
                is Condition.False -> return Deny(c.hint(data, conditionResult))
            }
        }
        return Permit
    }

    fun isApplicable(data: T): Condition {
        return applicable(data, hint)
    }

    fun explain(data: T, resolverContext: ResolverContext?, level: Int = 0, acc: MutableList<PolicyExplainRecord> = mutableListOf()): List<PolicyExplainRecord> {
        try {
            val resolvedData = resolverContext?.resolve(data) ?: data
            val applicability = isApplicable(resolvedData)
            if (applicability == Condition.True) {
                val result = evaluate(resolvedData, resolverContext)
                val conditionExplained = explainConditions(resolvedData)
                acc.add(PolicyExplainRecord(
                    hint(resolvedData, applicability), level, result, mergeStrategy, conditionExplained
                ))
            } else {
                acc.add(PolicyExplainRecord(
                    hint(resolvedData, applicability), level, NotApplicable(""), mergeStrategy, listOf()
                ))
            }
            explainNested(resolvedData, resolverContext, level, acc)
            return acc
        } catch (e: Exception) {
            throw generateException("explain", e, data)
        }
    }

    private fun explainNested(data: T, resolverContext: ResolverContext?, level: Int, acc: MutableList<PolicyExplainRecord>): Unit {
        for (nestedPolicy in this.policies) {
            nestedPolicy.explain(data, resolverContext, level + 1, acc)
        }
    }

    private fun explainConditions(data: T): List<ConditionExplainRecord> {
        val explanations = mutableListOf<ConditionExplainRecord>()
        for (c in conditions) {
            val conditionResult = c(data, hint)
            val hint = c.hint(data, conditionResult)
            explanations.add(ConditionExplainRecord(hint, conditionResult))
        }
        return explanations
    }

    private fun errorMessage(context: String, e: Exception, data: T): String {
        return when (e) {
            is NullPointerException -> "error evaluating policy ${context} on data = ${data}, most likely the data was not resolved properly"
            is EvaluationException -> "error evaluating policy ${context} on data = ${data}, nested policy failed"
            else -> "error evaluating policy ${context} on data = ${data}, unknown error"
        }
    }

    private fun generateException(context: String, e: Exception, data: T): EvaluationException {
        return EvaluationException(errorMessage(context, e, data), e)
    }

    /**
     * reversal are generally not sensitive to the latency of the calls, as used to generate filters which are then
     * applied in DBMS or something. So here unlike in the 'evaluate' we prefer some cleaner approach
     */
    fun evaluateReversal(data: T?, resolverContext: ResolverContext?): ReversalEvaluationResult {
        val resolved = resolverContext?.resolve(data)?:data
        val applicabilityCondition = evaluateApplicabilityReversal(resolved, resolverContext)
        if (applicabilityCondition is Condition.NotApplicable) return ReversalEvaluationResult(Condition.NotApplicable)
        val nestedPoliciesResponse = evaluateNestedReversal(resolved, resolverContext)
        return ReversalEvaluationResult(applicabilityCondition, nestedPoliciesResponse)
    }

    private fun evaluateApplicabilityReversal(data: T?, resolverContext: ResolverContext?): Condition {
        val r: Condition = try {
            if (data != null) {
                val applicability = isApplicable(data)
                when (applicability) {
                    is Condition.Undefined -> applicable.reversal(data, hint, resolverContext)
                    is Condition.True -> Condition.True
                    is Condition.False -> Condition.NotApplicable
                    else -> applicability
                }
            } else {
                applicable.reversal(data, hint, resolverContext)
            }
        } catch (e: Exception) {
            applicable.reversal(data, hint, resolverContext)
        }
        return r
    }

    private fun evaluateNestedReversal(data: T?, resolverContext: ResolverContext?): Condition {
        if (policies.isEmpty() && conditions.isEmpty()) { //terminal case, empty policy
            return when (mergeStrategy) {
                is FirstApplicable -> Condition.NotApplicable
                is DenyUnlessPermit -> Condition.False
                is PermitUnlessDeny -> Condition.True
            }
        } else if (policies.isEmpty()) { //terminal case, policy containing explicit conditions
            return Condition.And(conditions.map { evaluateConditionReversal(it, data, resolverContext) })//merging all as 'and'
        } else if (conditions.isEmpty()) {
            val policyReversals = policies.map { it.evaluateReversal(data, resolverContext) }
            return mergePolicyReversalConditions(policyReversals)
        } else {
            val conditionReversals = ReversalEvaluationResult(Condition.True, Condition.And(conditions.map { evaluateConditionReversal(it, data, resolverContext) }))
            val policyReversals = policies.map { it.evaluateReversal(data, resolverContext) }
            return mergePolicyReversalConditions(policyReversals + conditionReversals)
        }
    }

    private fun mergePolicyReversalConditions(policyReversalEvaluations: List<ReversalEvaluationResult>): Condition {
        if (policyReversalEvaluations.isEmpty()) return Condition.NotApplicable
        return when (mergeStrategy) {
            // a chained condition (policy 1 is applicable and then the result of the policy application) or
            //  (policy 1 is not applicable and (policy2 is applicable && then the result of the policy application) ...)
            is FirstApplicable -> {
                val filtered = policyReversalEvaluations.filterNot { it.applicabilityCondition is Condition.NotApplicable }
                if (filtered.isEmpty()) Condition.NotApplicable
                else {
                    combineFirstApplicableCondition(filtered)
                }
            }
            //here we're looking for at least one "permit", so we're just joining the conditions by 'or'
            is DenyUnlessPermit -> {
                val filtered = policyReversalEvaluations.filterNot { it.applicabilityCondition is Condition.NotApplicable }.map { it.conditionForPermit() }
                if (filtered.isEmpty()) Condition.NotApplicable else Condition.Or(filtered)
            }
            //here we're looking for at least one "deny", so we're just joining the conditions by 'and'
            is PermitUnlessDeny -> {
                val filtered = policyReversalEvaluations.filterNot { it.applicabilityCondition is Condition.NotApplicable }.map { it.conditionForPermit() }
                if (filtered.isEmpty()) Condition.NotApplicable else Condition.And(filtered)
            }
        }
    }

    private fun combineFirstApplicableCondition(evaluations: List<ReversalEvaluationResult>): Condition {
        //for the last policy in the list it will be (conditionForPermit() and all other policies are not applicable)
        //for the one before the last - the same with the list shortened by one, and so on
        val reversedList = evaluations.reversed()
        val conditions = reversedList.mapIndexed { index, reversalEvaluationResult ->
            val remainingPoliciesNotApplicable = reversedList.drop(index + 1).map { not(it.applicabilityCondition) }
            if (remainingPoliciesNotApplicable.isEmpty()) {
                reversalEvaluationResult.conditionForPermit()
            } else {
                Condition.And(remainingPoliciesNotApplicable + reversalEvaluationResult.conditionForPermit())
            }
        }
        return Condition.Or(conditions)
    }

    private fun evaluateConditionReversal(policyCondition: PolicyCondition<T>, data: T?, resolverContext: ResolverContext?): Condition {
        val directEvaluation = try {
            policyCondition(data, hint)
        } catch (e: Exception) {
            Condition.Undefined(hint())
        }
        return when (directEvaluation) {
            is Condition.True -> Condition.True
            is Condition.False -> Condition.False
            is Condition.Undefined -> policyCondition.reversal(data, hint, resolverContext)
            else -> directEvaluation
        }
    }

}

class EvaluationException(message: String, reason: Exception): RuntimeException(message, reason)


fun printExplain(explainRecord: List<PolicyExplainRecord>) {
    fun spacing(level: Int): String {
        return when (level) {
            0 -> ""
            1 -> "├ "
            else -> "├" + "─".repeat(level - 1) + " "
        }
    }
    for(r in explainRecord) {
        println(spacing(r.level) + "policy ${r.policyHint}  (merging sub-policies as ${r.mergeStrategy}) => ${r.result}")
        for (c in r.conditionHints) {
            println(spacing(r.level + 1) + " condition: ${c.conditionHint} => ${c.conditionResult}")
        }
    }
}

