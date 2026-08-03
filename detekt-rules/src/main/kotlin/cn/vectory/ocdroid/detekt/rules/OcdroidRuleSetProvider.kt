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
            ::AuthorityDirectWriteRule,             // §U-MN6 Batch 3
            ::UiMustNotImportDataApiRule,           // §wave0-ocdroid-2026-08-03
            ::DataMustNotImportUiRule,              // §wave0-ocdroid-2026-08-03
            ::NoRawDpLiteralRule,                   // §wave0-ocdroid-2026-08-03
            ::NoRawAlertDialogRule,                 // §wave0-ocdroid-2026-08-03
        ),
    )

    companion object {
        const val RULE_SET_ID = "ocdroid"
    }
}
