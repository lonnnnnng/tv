package com.example.tv.data

import android.content.Context
import org.json.JSONArray

data class TvChannel(val group: String, val name: String, val pages: List<String>) {
  val primaryPage: String get() = pages.firstOrNull().orEmpty()
}

object ChannelCatalog {
  fun load(context: Context): List<TvChannel> = runCatching {
    val json = context.assets.open("sources/base/sourceLive.plain.json").bufferedReader().use { it.readText() }
    val groups = JSONArray(json)
    buildList {
      for (groupIndex in 0 until groups.length()) {
        val group = groups.optJSONObject(groupIndex) ?: continue
        val groupName = group.optString("groupName").trim()
        val channels = group.optJSONArray("channelList") ?: continue
        for (index in 0 until channels.length()) {
          val item = channels.optJSONObject(index) ?: continue
          val name = item.optString("name").trim()
          val pages = item.optJSONArray("urls")?.let { urls ->
            (0 until urls.length()).map { urls.optString(it).trim() }.filter(String::isNotBlank).distinct()
          }.orEmpty()
          if (groupName.isNotBlank() && name.isNotBlank() && pages.isNotEmpty()) add(TvChannel(groupName, name, pages))
        }
      }
    }
  }.getOrElse {
    // 资源损坏时仍保留可验证的官方入口，用户可以启动应用并看到明确的可播放频道。作者：long
    listOf(TvChannel("央视", "CCTV-1 综合", listOf("https://tv.cctv.com/live/cctv1/")))
  }
}
