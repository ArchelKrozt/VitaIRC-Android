package io.github.archelkrozt.vitairc.irc

import io.github.archelkrozt.vitairc.data.Config
import io.github.archelkrozt.vitairc.net.Booru
import java.io.File
import java.util.Base64

/** Someone's VitaIRC profile, received over IRC (see PROFILE-PROTOCOL.md). */
data class Profile(
    val nick: String,
    val name: String = "",
    val bio: String = "",
    val avatar: ByteArray = ByteArray(0),       // JPEG
    val favs: List<ProfileFav> = emptyList(),
    val none: Boolean = false,                  // the owner doesn't share a profile
)

data class ProfileFav(val engine: Int, val id: String, val thumb: String) { val key get() = "$engine/$id" }

/** WHOIS answer gathered into one card. */
data class WhoisInfo(
    val nick: String,
    val user: String = "", val host: String = "", val realname: String = "", val server: String = "", val serverInfo: String = "",
    val account: String = "", val away: String = "", val channels: List<String> = emptyList(), val idleSecs: Int = -1,
    val signon: Long = 0, val secure: Boolean = false, val oper: Boolean = false, val actualHost: String = "", val found: Boolean = true,
)

/** My own profile: name, bio, avatar and which favorites are public. Stored on the device only. */
object MyProfile {
    const val MAX_NAME = 40
    const val MAX_BIO = 280
    const val MAX_FAVS = 12

    @Volatile var name = ""
    @Volatile var bio = ""
    @Volatile var share = true
    val publicFavs = ArrayList<String>()                    // "<engine>/<id>"

    private val file get() = File(Config.dir, "profile.txt")
    val avatarFile get() = File(Config.dir, "profile_avatar.jpg")
    val avatarBigFile get() = File(Config.dir, "profile_avatar_big.jpg")
    private val favsFile get() = File(Config.dir, "profile_favs.txt")

    @Synchronized
    fun load() {
        name = ""; bio = ""; share = true; publicFavs.clear()
        if (file.exists()) file.readLines().forEach { l ->
            val k = l.substringBefore('='); val v = l.substringAfter('=', "")
            when (k) {
                "name" -> name = v
                "bio" -> bio = v.replace("\\n", "\n")
                "share" -> share = v != "0"
            }
        }
        if (favsFile.exists()) publicFavs.addAll(favsFile.readLines().filter { it.contains('/') })
    }

    @Synchronized
    fun save() {
        try {
            file.writeText("name=${name.replace('\n', ' ')}\nbio=${bio.replace("\n", "\\n")}\nshare=${if (share) 1 else 0}\n")
            favsFile.writeText(publicFavs.joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }

    @Synchronized fun isPublic(key: String) = key in publicFavs

    /** Marks a favorite public or private. Returns false when the limit was reached. */
    @Synchronized
    fun setPublic(key: String, on: Boolean): Boolean {
        if (on) {
            if (key in publicFavs) return true
            if (publicFavs.size >= MAX_FAVS) return false
            publicFavs.add(key)
        } else publicFavs.remove(key)
        save()
        return true
    }

    val avatar: ByteArray get() = try { avatarFile.readBytes() } catch (e: Exception) { ByteArray(0) }

    /** The answer lines (without the CTCP wrapping). */
    @Synchronized
    fun answerLines(): List<String> {
        if (!share) return listOf("NONE")
        val out = arrayListOf("BEGIN 1")
        if (name.isNotEmpty()) out.add("NAME " + name.take(MAX_NAME))
        for (c in chunks(bio.take(MAX_BIO).replace('\n', ' '), 200)) out.add("BIO $c")
        val b64 = Base64.getEncoder().encodeToString(avatar)
        var i = 0
        while (i < b64.length) { out.add("AVATAR " + b64.substring(i, minOf(b64.length, i + 300))); i += 300 }
        val favs = Booru.favorites.value
        for (key in publicFavs.take(MAX_FAVS)) {
            val p = favs.firstOrNull { it.key == key } ?: continue
            val thumb = if (p.engine == Booru.SANKAKU || p.thumbUrl.isEmpty() || p.thumbUrl.toByteArray().size > 250) "-" else p.thumbUrl
            out.add("FAV ${p.engine} ${p.id} $thumb")
        }
        out.add("END")
        return out
    }

    /** Text cut at character boundaries into pieces of at most maxBytes UTF-8 bytes. */
    fun chunks(s: String, maxBytes: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val b = ch.toByteArray().size
            if (bytes + b > maxBytes) { out.add(cur.toString()); cur.setLength(0); bytes = 0 }
            cur.append(ch); bytes += b
            i += Character.charCount(cp)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}

/** A profile being received */
class ProfileBuild(val nick: String) {
    var started = false
    var name = ""
    val bio = StringBuilder()
    val avatarB64 = StringBuilder()
    val favs = ArrayList<ProfileFav>()
    val askedAt = TimeUtil.now()
}
