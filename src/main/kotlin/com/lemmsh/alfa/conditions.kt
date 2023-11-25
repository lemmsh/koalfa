
package com.lemmsh.alfa



/**
 * The single purpose of such a complex conditional DSL is to support reversal,
 * otherwise we would juts normally write logical expressions in Kotlin.
 *
 * The reversal means, we resolve everything that can be resolved and only keep the undefined part, intact, so the client can then take the
 * minimal logical tree and print it into an SQL condition or whatever necessary - and that's this SQL condition that needs to
 * be expressed with the below
 */

sealed class Condition {
    data class In(val parameter: Parameter, val list: ValueList) : Condition() {
        override fun toString(): String {
            return "${parameter.toString()} in (${list.joinToString()})"
        }
    }
    data class Equals(val parameter: Parameter, val other: String) : Condition() {
        override fun toString(): String {
            return "${parameter.toString()} = '$other'"
        }
    }

    data class NotIn(val parameter: Parameter, val list: ValueList) : Condition() {
        override fun toString(): String {
            return "${parameter.toString()} not in (${list.joinToString()})"
        }
    }
    data class NotEquals(val parameter: Parameter, val other: String) : Condition() {
        override fun toString(): String {
            return "${parameter.toString()} != '$other'"
        }
    }
    data class And(val conditions: List<Condition>) : Condition() {
        constructor(vararg conditions: Condition) : this(conditions.toList())

        override fun toString(): String {
            return conditions.joinToString(separator = " and ", prefix = "(", postfix = ")") { it.toString() }
        }
    }
    data class Or(val conditions: List<Condition>) : Condition() {
        constructor(vararg conditions: Condition) : this(conditions.toList())

        override fun toString(): String {
            return conditions.joinToString(separator = " or ", prefix = "(", postfix = ")") { it.toString() }
        }
    }
    data class Not(val condition: Condition) : Condition() {
        override fun toString(): String {
            return "not (${condition.toString()})"
        }
    }
    object True : Condition() {
        override fun toString(): String {
            return "true"
        }
    }
    object False : Condition() {
        override fun toString(): String {
            return "false"
        }
    }
    data class Undefined(val comment: String) : Condition() {
        override fun toString(): String {
            return "undefined($comment)"
        }
    }

    object NotApplicable : Condition() {
        override fun toString(): String {
            return "not_applicable"
        }
    }

    fun simplify(): Condition {
        var simplified = Companion.simplify(this)
        repeat(9) { //just in case. Actually may need to maje it a parameter
            val nextSimplified = Companion.simplify(simplified)
            if (nextSimplified == simplified) {
                return simplified
            }
            simplified = nextSimplified
        }
        return simplified
    }

    companion object {
        fun simplify(condition: Condition): Condition = when (condition) {
            is And -> {
                val simplifiedConditions = condition.conditions.map { simplify(it) }.filterNot { it is NotApplicable }
                when {
                    simplifiedConditions.isEmpty() -> NotApplicable
                    simplifiedConditions.all { it is True  } -> True
                    simplifiedConditions.any { it is False } -> False
                    else -> And(simplifiedConditions.filterNot { it is True })
                }
            }
            is Or -> {
                val simplifiedConditions = condition.conditions.map { simplify(it) }.filterNot { it is NotApplicable }
                when {
                    simplifiedConditions.isEmpty() -> NotApplicable
                    simplifiedConditions.any { it is True } -> True
                    simplifiedConditions.all { it is False } -> False
                    else -> Or(simplifiedConditions.filterNot { it is False })
                }
            }
            is Not -> when (val simplified = simplify(condition.condition)) {
                is True -> False
                is False -> True
                is NotIn -> In(simplified.parameter, simplified.list)
                is NotEquals -> Equals(simplified.parameter, simplified.other)
                is In -> NotIn(simplified.parameter, simplified.list)
                is Equals -> NotEquals(simplified.parameter, simplified.other)
                is Not -> simplified.condition
                else -> Not(simplified)
            }
            else -> condition
        }
        
    }

}
