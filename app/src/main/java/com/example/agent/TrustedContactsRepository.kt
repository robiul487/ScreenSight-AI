package com.example.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.trustedContactsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "screensight_trusted_contacts"
)

/**
 * Local Trusted Family Contacts Repository (Section 10).
 *
 * Allows configuring family members with Bangla/English nicknames (e.g., "বড় ছেলে", "ছোট ছেলে", "স্ত্রী"),
 * resolves spoken queries safely without ever guessing phone numbers, and optionally searches
 * Android's ContactsProvider only if the user explicitly granted READ_CONTACTS.
 */
class TrustedContactsRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.trustedContactsDataStore
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    sealed class ContactLookupResult {
        data class SingleMatch(val contact: TrustedContact, val fromTrustedList: Boolean) : ContactLookupResult()
        data class Ambiguous(val candidates: List<TrustedContact>) : ContactLookupResult()
        object NotFound : ContactLookupResult()
    }

    val trustedContactsFlow: Flow<List<TrustedContact>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_TRUSTED_CONTACTS_JSON].orEmpty()
        if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<TrustedContact>>(raw) }.getOrDefault(emptyList())
        }
    }

    suspend fun addOrUpdateContact(
        name: String,
        phoneNumber: String,
        nickname: String = "",
        relationship: String = ""
    ): TrustedContact? {
        val cleanName = name.trim()
        val cleanPhone = phoneNumber.trim()
        if (cleanName.isEmpty() || cleanPhone.isEmpty()) return null

        val newContact = TrustedContact(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            nickname = nickname.trim(),
            relationship = relationship.trim(),
            phoneNumber = cleanPhone
        )
        dataStore.edit { prefs ->
            val current = prefs[KEY_TRUSTED_CONTACTS_JSON]
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.decodeFromString<List<TrustedContact>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val updated = current.filterNot {
                it.name.equals(cleanName, ignoreCase = true) && it.phoneNumber == cleanPhone
            } + newContact
            prefs[KEY_TRUSTED_CONTACTS_JSON] = json.encodeToString(updated)
        }
        return newContact
    }

    suspend fun removeContact(contactId: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_TRUSTED_CONTACTS_JSON]
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.decodeFromString<List<TrustedContact>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val updated = current.filterNot { it.id == contactId }
            prefs[KEY_TRUSTED_CONTACTS_JSON] = json.encodeToString(updated)
        }
    }

    /**
     * Resolves a spoken contact query (name, Bangla nickname, or relationship).
     * Never guesses a phone number; returns Ambiguous when multiple matches exist.
     */
    suspend fun resolveContact(rawQuery: String): ContactLookupResult {
        val query = rawQuery.trim()
        if (query.isEmpty()) return ContactLookupResult.NotFound
        val lower = query.lowercase()
        val normalizedTokens = expandRelationshipAliases(lower)

        val trusted = trustedContactsFlow.first()
        val exactTrusted = trusted.filter { contact ->
            contact.name.equals(query, ignoreCase = true) ||
                contact.nickname.equals(query, ignoreCase = true) ||
                contact.relationship.equals(query, ignoreCase = true)
        }
        if (exactTrusted.size == 1) {
            return ContactLookupResult.SingleMatch(exactTrusted.first(), fromTrustedList = true)
        }
        if (exactTrusted.size > 1) {
            return ContactLookupResult.Ambiguous(exactTrusted)
        }

        val partialTrusted = trusted.filter { contact ->
            val haystack = listOf(contact.name, contact.nickname, contact.relationship)
                .joinToString(" ")
                .lowercase()
            normalizedTokens.any { token -> token.isNotBlank() && haystack.contains(token) }
        }
        if (partialTrusted.size == 1) {
            return ContactLookupResult.SingleMatch(partialTrusted.first(), fromTrustedList = true)
        }
        if (partialTrusted.size > 1) {
            return ContactLookupResult.Ambiguous(partialTrusted)
        }

        // Optional fallback to Android ContactsProvider ONLY if READ_CONTACTS is granted
        if (hasReadContactsPermission()) {
            val deviceMatches = searchDeviceContacts(query)
            return when (deviceMatches.size) {
                0 -> ContactLookupResult.NotFound
                1 -> ContactLookupResult.SingleMatch(deviceMatches.first(), fromTrustedList = false)
                else -> ContactLookupResult.Ambiguous(deviceMatches.take(5))
            }
        }

        return ContactLookupResult.NotFound
    }

    fun hasReadContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun searchDeviceContacts(query: String): List<TrustedContact> {
        val matches = mutableListOf<TrustedContact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seen = HashSet<String>()
                while (cursor.moveToNext() && matches.size < 6) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                    val number = if (numIdx >= 0) cursor.getString(numIdx).orEmpty().trim() else ""
                    if (name.isNotEmpty() && number.isNotEmpty() && seen.add("$name|$number")) {
                        matches.add(
                            TrustedContact(
                                name = name,
                                phoneNumber = number
                            )
                        )
                    }
                }
            }
        }
        return matches
    }

    companion object {
        private val KEY_TRUSTED_CONTACTS_JSON = stringPreferencesKey("trusted_contacts_json")

        fun expandRelationshipAliases(lowerQuery: String): Set<String> {
            val tokens = linkedSetOf(lowerQuery)
            val aliasGroups = listOf(
                listOf("ছেলে", "ছেলেকে", "ছেলেডারে", "পুত্র", "son"),
                listOf("বড় ছেলে", "বড় ছেলে", "elder son", "big son"),
                listOf("ছোট ছেলে", "ছোট ছেলেডারে", "younger son", "small son"),
                listOf("মেয়ে", "মেয়ে", "মেয়েকে", "মেয়েকে", "daughter"),
                listOf("স্ত্রী", "বউ", "ওয়াইফ", "wife"),
                listOf("স্বামী", "husband"),
                listOf("ভাই", "বড় ভাই", "বড় ভাই", "ছোট ভাই", "brother"),
                listOf("বোন", "আপা", "sister"),
                listOf("বাবা", "আব্বু", "আব্বা", "father", "dad"),
                listOf("মা", "আম্মু", "আম্মা", "mother", "mom"),
                listOf("ডাক্তার", "doctor")
            )
            for (group in aliasGroups) {
                if (group.any { lowerQuery.contains(it) }) {
                    tokens.addAll(group)
                }
            }
            return tokens
        }
    }
}
