# Koalfa

Koalfa is an experimental Domain Specific Language (DSL) inspired by the ALFA language (https://en.wikipedia.org/wiki/Abbreviated_Language_for_Authorization) which is designed for access control systems. Similar to a company's rulebook, ABAC systems like ALFA and XACML manage digital access rights. 
A DSL for access control provides a mental model which is natural for those familiar with regulations (e.g., lawyers, managers), and thus make it way easier for the developers to understand them and cater for them.

Koalfa is tailored to overcome some of the limitations of ALFA, particularly its inability to reuse policies as functions within policy sets. 
Additionally, Koalfa introduces a concept of policy reversal, which is particularly useful for integrating access control policies with databases. This allows for the application of policies to not just individual access requests but also to database mechanisms like ClickHouse row-level policies.

This is a research project, but it may be used in production with seemingly minimal effort. If you decide to use Koalfa in production, please share your experiences through pull requests or issues. 

The codebase is structured into several Kotlin files:

- `policies.kt`: Contains examples of policies defined using the DSL, covering cases such as direct evaluation, explanation, and reversal. It demonstrates how the DSL can be used to express complex policy logic in Koalfa
- `dsl.kt`: Defines the DSL's syntax sugar
- `engine.kt`: Contains the evaluation engine that interprets and executes the policies
- `conditions.kt`: Implements the logic condition DSL
- `tests.kt`: A minimal test suite

The DSL aims to use a minimal set of dependencies, relying only on the standard Kotlin library.

## Base Concepts

**Policy**: A policy is a set of rules that determine the access control decisions. It is defined using the DSL and can contain multiple conditions and nested policies. Policies can be **encapsulated** within other policies as nested, allowing for complex policy structures where the evaluation of one policy can depend on the results of another. 

**Merge Strategies** determine how the results of multiple policy evaluations are combined. 'FirstApplicable' stops at the first policy that applies (applicability is being decided on based on the 'applicableWhen' condition), 'DenyUnlessPermit' denies access unless any policy explicitly permits, and 'PermitUnlessDeny' permits access unless any policy explicitly denies. 
Policies may also contain explicit **conditions** - logical expressions within a policy that evaluate to true or false based on the attributes of the **argument**.

The **argument** is a data object that a policy evaluates. It contains the attributes that the policy conditions check against. **Resolvers** enrich the arguments with additional data required for policy evaluation. **Resolvers** abstract the data retrieval and are used to fetch necessary data for policy evaluation and to support testing with different data sources.

Each policy also can be **explained** graphically to show its decision tree

## Resolvers in Koalfa

In standard policy evaluation, the resolvers supply the necessary data without being explicitly mentioned in the policy definitions. This leads to cleaner and more maintainable code. For reversal conditions, where not all attributes are known, resolvers must be explicitly defined to generate the conditions for a 'Permit' decision. 
This explicit definition is demonstrated in `tests.kt` and `policies.kt`, where resolvers contribute to the formulation of database filters or similar mechanisms.





