package com.studyontv.tv

import android.content.Context
import org.json.JSONObject

/** Isolated upgrade regression; never modifies the household database. */
object SpeechUpgradeRegression {
    fun run(context: Context) {
        val name = "speech-upgrade-regression.db"
        context.deleteDatabase(name)
        val store = LocalStore(context, name)
        try {
            store.set("speech_settings", JSONObject().put("mode", "custom")
                .put("autoRead", true).put("readLanguage", "zh-CN").put("readScope", "questionOptions")
                .put("customEndpoint", "https://example.test/v1").put("customModel", "old-model")
                .put("customVoice", "old-voice").put("customFormat", "wav").put("customKey", "old-secret")
                .put("azureKey", "existing-azure-secret").toString())
            val settings = SpeechReader.settings(store)
            check(settings.getString("mode") == "system")
            check(settings.getBoolean("autoRead"))
            check(settings.getString("readLanguage") == "zh-CN")
            check(settings.getString("readScope") == "questionOptions")
            check(settings.keys().asSequence().none { it.startsWith("custom") })
            check(!settings.toString().contains("old-secret"))
            check(!settings.toString().contains("existing-azure-secret"))
            check(settings.getBoolean("azureKeySet"))
            SpeechReader.save(store, settings)
            val persisted = JSONObject(store.get("speech_settings", "{}"))
            check(!persisted.has("customKey"))
            check(persisted.getString("azureKey") == "existing-azure-secret")
            check(persisted.getBoolean("autoRead"))
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
