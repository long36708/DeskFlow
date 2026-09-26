package com.deskflow.data.classifier

import android.content.Context
import com.deskflow.domain.classifier.Category
import com.deskflow.domain.classifier.CategoryRules
import org.json.JSONObject

/**
 * 读取 assets/category_mapping.json 作为用户自定义分类规则，与内置规则合并后覆盖之。
 * 文件缺失或格式错误时不抛异常，退化为「无自定义规则」。
 */
object CategoryMappingLoader {

    private const val ASSET = "category_mapping.json"

    fun load(context: Context): CategoryRules = runCatching {
        context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }
    }.getOrDefault(CategoryRules())

    fun parse(json: String): CategoryRules {
        val root = JSONObject(json)
        return CategoryRules(
            exactPackages = readMap(root.optJSONObject("exactPackages")),
            keywords = readMap(root.optJSONObject("keywords")),
            titleKeywords = readMap(root.optJSONObject("titleKeywords")),
        )
    }

    private fun readMap(obj: JSONObject?): Map<String, Category> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Category>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val id = obj.optString(k).orEmpty()
            Category.byId(id)?.let { out[k.trim().lowercase()] = it }
        }
        return out
    }
}
