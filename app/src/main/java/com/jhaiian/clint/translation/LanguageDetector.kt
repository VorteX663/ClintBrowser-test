package com.jhaiian.clint.translation

import java.util.Locale

internal object LanguageDetector {

    private data class Detection(val code: String, val confident: Boolean)

    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "en" to "the and of to is in that it for with was on are this you be have not they from by at".words(),
        "es" to "de la que el en los del se las por un una para con es más como pero sus está muy también".words(),
        "fr" to "le la les de des et est une un du que qui dans pour pas sur au avec ce sont aux mais".words(),
        "de" to "der die und das ist nicht den mit von sich des auf ein eine für dem auch wird sind wir".words(),
        "it" to "il di che la è per un una del della non sono con gli nel anche più questo alla".words(),
        "pt" to "de que não uma um os do da em para com é se no na as mais por você são também".words(),
        "nl" to "de het een en van is dat niet op te zijn met voor die ook aan er maar wordt".words(),
        "pl" to "i w nie na się z że do jest to jak o ale co po dla są czy tak już".words(),
        "tr" to "ve bir bu için ile de da çok gibi daha olarak ama ne var değil sonra kadar olan".words(),
        "sv" to "och att det är som en på för med av inte till den har om jag men ett".words(),
        "da" to "og at det er en til på med for af ikke den som har de et men jeg".words(),
        "nb" to "og det er å på en til som for med ikke av har den jeg de et ble".words(),
        "fi" to "ja on ei että se oli hän mutta kun niin myös ovat tai joka tämä kuin".words(),
        "cs" to "a je se na že to v s z do jsou jako pro ale by není také které".words(),
        "sk" to "a je sa na že to v s z do sú ako pre ale by nie aj ktoré".words(),
        "ro" to "și de în la cu nu că pe din este o un sunt mai pentru care ca".words(),
        "hu" to "a az és hogy nem is egy van meg de ez már csak mint el volt".words(),
        "id" to "dan yang di ini itu dengan untuk tidak dari dalam akan pada juga ada adalah bisa".words(),
        "ms" to "dan yang di ini itu dengan untuk tidak dari dalam akan pada juga ada ialah boleh".words(),
        "vi" to "là và của có không những các được cho một người trong với này đã".words(),
        "ca" to "el la i de que és els les per amb una no del als però més".words(),
        "hr" to "je i u na da se za su od ne kao što ali koji ili".words(),
        "sl" to "je in da na se za so ki ne pa tudi od kot".words(),
        "lt" to "ir kad yra į su ne tai bet kaip iš".words(),
        "lv" to "un ir ka ar uz no par kas bet arī tas".words(),
        "et" to "ja on ei et see kui ka oli mis aga siis".words(),
        "tl" to "ang ng mga sa na at ay ito para hindi siya ko ako".words(),
        "af" to "die en van is het in nie op wat vir om met ek dat".words(),
        "sq" to "dhe të në një për me që nga është nuk".words(),
        "is" to "og að er í á sem til við ekki um það".words(),
        "gl" to "o a de que e en os un unha non para do da con".words(),
        "eu" to "eta da ez du bat ere dira zen baina hori".words(),
        "az" to "və bir bu üçün ilə da də çox daha olan ki".words()
    )

    private const val SIMPLIFIED_CHARS = "这们说时会国来对发过后还为个东车长门问间"
    private const val TRADITIONAL_CHARS = "這們說時會國來對發過後還為個東車長門問間"

    private fun String.words(): Set<String> = split(' ').filter { it.isNotBlank() }.toSet()

    fun detect(htmlLang: String?, text: String): String? {
        val declared = ClintTranslations.normalize(htmlLang)
        val detected = detectText(text)
        return when {
            detected == null -> declared
            detected.confident && declared != null && sameFamily(declared, detected.code) -> declared
            detected.confident -> detected.code
            else -> declared ?: detected.code
        }
    }

    private fun sameFamily(first: String, second: String): Boolean =
        first.substringBefore('-').equals(second.substringBefore('-'), ignoreCase = true) ||
            setOf(first, second) == setOf("hr", "sr") || setOf(first, second) == setOf("hr", "bs") ||
            setOf(first, second) == setOf("id", "ms")

    private fun detectText(text: String): Detection? {
        if (text.isBlank()) return null
        val counts = HashMap<String, Int>()
        var letters = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            letters++
            val script = scriptOf(codePoint) ?: continue
            counts[script] = (counts[script] ?: 0) + 1
        }
        if (letters < 20) return null
        val dominant = counts.maxByOrNull { it.value }
        val latinShare = (letters - counts.values.sum()).toDouble() / letters
        if (dominant == null || latinShare > 0.6) return detectLatin(text)
        val share = dominant.value.toDouble() / letters
        return when (dominant.key) {
            "kana" -> Detection("ja", true)
            "han" -> if ((counts["kana"] ?: 0) > dominant.value / 20) Detection("ja", true) else Detection(chineseVariant(text), share > 0.3)
            "cyrillic" -> Detection(cyrillicVariant(text), share > 0.3)
            "arabic" -> Detection(arabicVariant(text), share > 0.3)
            "devanagari" -> Detection(if (Regex("आहे|आणि|च्या|ला ").containsMatchIn(text)) "mr" else "hi", share > 0.3)
            else -> Detection(dominant.key, share > 0.3)
        }
    }

    private fun scriptOf(codePoint: Int): String? = when (codePoint) {
        in 0x3040..0x30FF, in 0x31F0..0x31FF -> "kana"
        in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> "ko"
        in 0x4E00..0x9FFF, in 0x3400..0x4DBF, in 0xF900..0xFAFF -> "han"
        in 0x0400..0x04FF, in 0x0500..0x052F -> "cyrillic"
        in 0x0600..0x06FF, in 0x0750..0x077F, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> "arabic"
        in 0x0590..0x05FF -> "he"
        in 0x0370..0x03FF, in 0x1F00..0x1FFF -> "el"
        in 0x0E00..0x0E7F -> "th"
        in 0x0900..0x097F -> "devanagari"
        in 0x0980..0x09FF -> "bn"
        in 0x0A80..0x0AFF -> "gu"
        in 0x0B80..0x0BFF -> "ta"
        in 0x0C00..0x0C7F -> "te"
        in 0x0C80..0x0CFF -> "kn"
        in 0x0D00..0x0D7F -> "ml"
        in 0x10A0..0x10FF -> "ka"
        in 0x0530..0x058F -> "hy"
        else -> null
    }

    private fun chineseVariant(text: String): String {
        val simplified = text.count { it in SIMPLIFIED_CHARS }
        val traditional = text.count { it in TRADITIONAL_CHARS }
        return if (traditional > simplified) "zh-Hant" else "zh-Hans"
    }

    private fun cyrillicVariant(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val scores = mapOf(
            "uk" to lower.count { it == 'і' || it == 'ї' || it == 'є' || it == 'ґ' } * 3,
            "be" to lower.count { it == 'ў' } * 6 + lower.count { it == 'і' },
            "sr" to lower.count { it == 'ђ' || it == 'ј' || it == 'љ' || it == 'њ' || it == 'ћ' || it == 'џ' } * 3,
            "ru" to lower.count { it == 'ы' || it == 'э' || it == 'ё' } * 2,
            "bg" to lower.count { it == 'ъ' }
        )
        val best = scores.maxByOrNull { it.value }
        return if (best == null || best.value == 0) "ru" else best.key
    }

    private fun arabicVariant(text: String): String {
        val ug = text.count { it == 'ې' || it == 'ۆ' || it == 'ۇ' || it == 'ۈ' || it == 'ۋ' || it == 'ە' }
        val ur = text.count { it == 'ٹ' || it == 'ڈ' || it == 'ڑ' || it == 'ں' || it == 'ے' || it == 'ھ' }
        val fa = text.count { it == 'پ' || it == 'چ' || it == 'ژ' || it == 'گ' || it == 'ی' || it == 'ک' }
        val ar = text.count { it == 'ة' || it == 'ى' || it == 'ي' || it == 'ك' }
        return listOf("ug" to ug * 3, "ur" to ur * 2, "fa" to fa, "ar" to ar).maxByOrNull { it.second }?.first ?: "ar"
    }

    private fun detectLatin(text: String): Detection? {
        val tokens = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (tokens.size < 5) return null
        val scores = STOPWORDS.mapValues { (_, words) -> tokens.count { it in words } }
        val sorted = scores.entries.sortedByDescending { it.value }
        val best = sorted.firstOrNull() ?: return null
        if (best.value == 0) return null
        val second = sorted.getOrNull(1)?.value ?: 0
        val confident = best.value >= 4 && best.value >= second * 1.3 && best.value.toDouble() / tokens.size > 0.05
        return Detection(best.key, confident)
    }
}
