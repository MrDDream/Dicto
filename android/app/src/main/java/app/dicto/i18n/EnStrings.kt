package app.dicto.i18n

/**
 * Anglais de chaque texte source français ([tr]), lu une fois dans `/app/dicto/i18n/en.tsv` (ressource Java, chemin absolu : R8 peut renommer
 * cette classe ; `français<TAB>anglais` par ligne). Un fichier de données plutôt qu'un gros `mapOf` : le lint
 * d'Android s'enlise sur des centaines de paires dans une seule expression.
 */
internal object EnStrings {
    val map: Map<String, String> by lazy {
        val stream = EnStrings::class.java.getResourceAsStream("/app/dicto/i18n/en.tsv") ?: return@lazy emptyMap()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap(HashMap())
        }
    }
}
