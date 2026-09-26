package com.deskflow.domain.classifier

import com.deskflow.domain.model.ItemState

object AppClassifier {

    fun classify(item: ItemState, rules: CategoryRules = DefaultRules.rules): Category {
        val pkg = item.componentPackage()?.lowercase()?.trim()
        if (!pkg.isNullOrEmpty()) {
            rules.exactPackages[pkg]?.let { return it }
            matchKeyword(pkg, rules.keywords)?.let { return it }
        }
        val title = item.title.trim()
        if (title.isNotEmpty()) {
            matchTitle(title, rules.titleKeywords)?.let { return it }
        }
        return Category.OTHER
    }

    /** 包名按分隔符切段，段内包含关键词即命中；多个命中取最长关键词。 */
    private fun matchKeyword(pkg: String, keywords: Map<String, Category>): Category? {
        if (keywords.isEmpty()) return null
        val segments = pkg.split('.', '_', '-', '/')
        var bestLen = -1
        var best: Category? = null
        for (seg in segments) {
            for ((kw, cat) in keywords) {
                if (kw.length > bestLen && seg.contains(kw)) {
                    bestLen = kw.length
                    best = cat
                }
            }
        }
        return best
    }

    private fun matchTitle(title: String, keywords: Map<String, Category>): Category? {
        val lower = title.lowercase()
        var bestLen = -1
        var best: Category? = null
        for ((kw, cat) in keywords) {
            if (kw.length > bestLen && lower.contains(kw.lowercase())) {
                bestLen = kw.length
                best = cat
            }
        }
        return best
    }
}
