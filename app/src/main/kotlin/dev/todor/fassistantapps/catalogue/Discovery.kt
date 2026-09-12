package dev.todor.fassistantapps.catalogue

import dev.todor.fassistantapps.BuildConfig
import dev.todor.fassistantapps.net.Http
import org.json.JSONArray

/**
 * Finds the apps, by asking GitHub which of the owner's public repositories carry the family topic.
 *
 * The topic is the entire list. Shipping a new app means putting that topic on its repository, and
 * the catalogue picks it up on the next refresh with nothing edited and nothing re-released.
 */
object Discovery {

    // One page is plenty for a personal account, and asking for a second would spend another
    // request out of an allowance of sixty an hour.
    private const val URL =
        "https://api.github.com/users/${BuildConfig.CATALOGUE_OWNER}/repos?per_page=100&sort=full_name"

    fun repositories(): List<String> {
        val repos = JSONArray(Http.text(URL, accept = "application/vnd.github+json"))
        return (0 until repos.length())
            .map { repos.getJSONObject(it) }
            .filter { repo ->
                val topics = repo.optJSONArray("topics") ?: return@filter false
                (0 until topics.length()).any { topics.getString(it) == BuildConfig.CATALOGUE_TOPIC }
            }
            .map { it.getString("name") }
    }
}
