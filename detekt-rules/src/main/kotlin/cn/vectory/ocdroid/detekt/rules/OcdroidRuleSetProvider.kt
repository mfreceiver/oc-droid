package cn.vectory.ocdroid.detekt.rules

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetProvider

/**
 * §sm-hardening B10: registers ocdroid's custom architecture-guarding rules.
 * Registered via META-INF/services/dev.detekt.api.RuleSetProvider.
 *
 * RuleSet.Id + RuleSet.invoke(List) are the detekt 2.0.0-alpha.0 API.
 */
class OcdroidRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSet.Id = RuleSet.Id(RULE_SET_ID)

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ::SessionStatusDirectWriteRule,
            ::AuthorityDirectWriteRule,           // §U-MN6 Batch 3
        ),
    )

    companion object {
        const val RULE_SET_ID = "ocdroid"
    }
}
