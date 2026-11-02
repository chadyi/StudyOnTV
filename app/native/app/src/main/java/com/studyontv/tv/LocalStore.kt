package com.studyontv.tv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID




class LocalStore(context: Context) : SQLiteOpenHelper(context, "studyontv.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE questions(
                 id TEXT PRIMARY KEY, subject TEXT, level TEXT, difficulty INTEGER,
                 stem TEXT NOT NULL, options TEXT NOT NULL, answer INTEGER NOT NULL,
                 tip TEXT, enabled INTEGER DEFAULT 1, created_at INTEGER)"""
        )
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, payload TEXT, ts INTEGER)")
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    
    fun get(key: String, default: String = ""): String {
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) ?: default else default
        }
    }

    fun set(key: String, value: String) {
        writableDatabase.execSQL(
            "INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
            arrayOf(key, value)
        )
    }

    
    fun adminToken(): String {
        var token = get("admin_token")
        if (token.isEmpty()) {
            token = (100000..999999).random().toString()
            set("admin_token", token)
        }
        return token
    }

    
    fun config(): JSONObject {
        val raw = get("config")
        if (raw.isEmpty()) {
            return JSONObject()
                .put("dailyMinutes", 40)
                .put("sessionMinutes", 10)
                .put("questionsPerRound", 5)
                .put("roundsPerDay", 3)
                .put("passRatio", 0.6)
                .put("windowStart", "18:30")
                .put("windowEnd", "20:00")
                .put("level", "三年级")
                .put("subjects", JSONArray(listOf("数学", "语文", "英语")))
                .put("version", 1)
        }
        return JSONObject(raw)
    }

    fun saveConfig(input: JSONObject): JSONObject {
        val cur = config()
        val next = JSONObject(cur.toString())
        listOf("dailyMinutes", "sessionMinutes", "questionsPerRound", "roundsPerDay", "windowStart", "windowEnd", "level").forEach {
            if (input.has(it)) next.put(it, input.get(it))
        }
        if (input.has("passRatio")) next.put("passRatio", input.getDouble("passRatio"))
        if (input.has("subjects")) next.put("subjects", input.getJSONArray("subjects"))
        next.put("version", cur.optInt("version", 1) + 1)
        set("config", next.toString())
        return next
    }

    
    fun questions(subject: String? = null, level: String? = null, limit: Int = 200): JSONArray {
        val where = StringBuilder("enabled=1")
        val args = mutableListOf<String>()
        if (!subject.isNullOrEmpty()) { where.append(" AND subject=?"); args.add(subject) }
        if (!level.isNullOrEmpty()) { where.append(" AND level=?"); args.add(level) }
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT * FROM questions WHERE $where ORDER BY RANDOM() LIMIT $limit",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                out.put(
                    JSONObject()
                        .put("id", c.getString(c.getColumnIndexOrThrow("id")))
                        .put("subject", c.getString(c.getColumnIndexOrThrow("subject")))
                        .put("level", c.getString(c.getColumnIndexOrThrow("level")))
                        .put("difficulty", c.getInt(c.getColumnIndexOrThrow("difficulty")))
                        .put("q", c.getString(c.getColumnIndexOrThrow("stem")))
                        .put("a", JSONArray(c.getString(c.getColumnIndexOrThrow("options"))))
                        .put("k", c.getInt(c.getColumnIndexOrThrow("answer")))
                        .put("tip", c.getString(c.getColumnIndexOrThrow("tip")) ?: "")
                )
            }
        }
        return out
    }

    fun countQuestions(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    
    fun upsertQuestion(item: JSONObject): String {
        val stem = item.optString("q").ifEmpty { throw IllegalArgumentException("缺少题干 q") }
        val options = item.optJSONArray("a") ?: throw IllegalArgumentException("缺少选项 a")
        if (options.length() < 2) throw IllegalArgumentException("选项至少 2 个")
        val answer = item.optInt("k", -1)
        if (answer < 0 || answer >= options.length()) throw IllegalArgumentException("答案下标 k 越界")
        val id = item.optString("id").ifEmpty { "q_" + UUID.randomUUID().toString().take(8) }
        val values = ContentValues().apply {
            put("id", id)
            put("subject", item.optString("subject", "常识"))
            put("level", item.optString("level", "三年级"))
            put("difficulty", item.optInt("difficulty", 2))
            put("stem", stem)
            put("options", options.toString())
            put("answer", answer)
            put("tip", item.optString("tip", ""))
            put("enabled", 1)
        }
        writableDatabase.insertWithOnConflict("questions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return id
    }

    fun importQuestions(items: JSONArray): Int {
        var added = 0
        writableDatabase.beginTransaction()
        try {
            for (i in 0 until items.length()) {
                try {
                    upsertQuestion(items.getJSONObject(i))
                    added++
                } catch (e: Exception) {
                    
                }
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return added
    }

    fun deleteQuestion(id: String): Boolean =
        writableDatabase.delete("questions", "id=?", arrayOf(id)) > 0

    
    fun logEvent(type: String, payload: JSONObject) {
        writableDatabase.execSQL(
            "INSERT INTO events(type,payload,ts) VALUES(?,?,?)",
            arrayOf(type, payload.toString(), System.currentTimeMillis())
        )
    }

    fun recentEvents(limit: Int = 50): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT type,payload,ts FROM events ORDER BY id DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) {
                out.put(
                    JSONObject()
                        .put("type", c.getString(0))
                        .put("payload", c.getString(1))
                        .put("ts", c.getLong(2))
                )
            }
        }
        return out
    }

    
    fun todayStats(): JSONObject {
        val start = startOfToday()
        var watch = 0
        var answered = 0
        var correct = 0
        readableDatabase.rawQuery(
            "SELECT type,payload FROM events WHERE ts>=?", arrayOf(start.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val type = c.getString(0)
                val p = try { JSONObject(c.getString(1)) } catch (e: Exception) { JSONObject() }
                when (type) {
                    "watch" -> watch += p.optInt("minutes", 0)
                    "quiz" -> {
                        answered += p.optInt("total", 0)
                        correct += p.optInt("correct", 0)
                    }
                }
            }
        }
        return JSONObject()
            .put("usedMinutes", watch)
            .put("answered", answered)
            .put("correct", correct)
            .put("accuracy", if (answered > 0) correct * 100 / answered else 0)
    }


    fun roundsDoneToday(): Int {
        val start = startOfToday()
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE type='quiz_pass' AND ts>=?",
            arrayOf(start.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun historyDays(days: Int = 7): JSONArray {
        val out = JSONArray()
        val cal = java.util.Calendar.getInstance()
        for (d in 0 until days) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -d)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val dayStart = cal.timeInMillis
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            val dayEnd = cal.timeInMillis

            var watch = 0; var answered = 0; var correct = 0; var rounds = 0
            readableDatabase.rawQuery(
                "SELECT type,payload FROM events WHERE ts>=? AND ts<?",
                arrayOf(dayStart.toString(), dayEnd.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(0)
                    val p = try { org.json.JSONObject(c.getString(1)) } catch (e: Exception) { org.json.JSONObject() }
                    when (type) {
                        "watch" -> watch += p.optInt("minutes", 0)
                        "quiz"  -> { answered += p.optInt("total", 0); correct += p.optInt("correct", 0) }
                        "quiz_pass" -> rounds++
                    }
                }
            }
            val label = if (d == 0) "今天" else if (d == 1) "昨天" else {
                val c2 = java.util.Calendar.getInstance()
                c2.timeInMillis = dayStart
                (c2.get(java.util.Calendar.MONTH)+1).toString() + "/" + c2.get(java.util.Calendar.DAY_OF_MONTH)
            }
            out.put(JSONObject()
                .put("label", label)
                .put("watch", watch)
                .put("answered", answered)
                .put("correct", correct)
                .put("rounds", rounds)
                .put("accuracy", if (answered > 0) correct * 100 / answered else 0)
            )
        }
        return out
    }

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
