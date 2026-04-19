package com.palmier.app

import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ContactsHandler {

    private const val TAG = "PalmierContacts"

    fun handleReadContacts(context: Context, data: Map<String, String>) {
        val requestId = data["requestId"] ?: return
        val hostId = data["hostId"] ?: return

        if (!CapabilityState.isEnabled(context, "contacts")) {
            postResponse(requestId, hostId, JSONObject().put("error", "Contacts access disabled by user"))
            return
        }

        Thread {
            try {
                val contacts = readContacts(context)
                postResponse(requestId, hostId, JSONObject().put("contacts", contacts))
            } catch (e: SecurityException) {
                Log.e(TAG, "Contacts permission not granted", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Contacts permission not granted"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read contacts", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Failed to read contacts: ${e.message}"))
            }
        }.start()
    }

    fun handleCreateContact(context: Context, data: Map<String, String>) {
        val requestId = data["requestId"] ?: return
        val hostId = data["hostId"] ?: return
        val name = data["name"]
        val phone = data["phone"]
        val email = data["email"]

        if (!CapabilityState.isEnabled(context, "contacts")) {
            postResponse(requestId, hostId, JSONObject().put("error", "Contacts access disabled by user"))
            return
        }

        if (name.isNullOrBlank()) {
            postResponse(requestId, hostId, JSONObject().put("error", "name is required"))
            return
        }

        Thread {
            try {
                createContact(context, name, phone, email)
                postResponse(requestId, hostId, JSONObject().put("ok", true))
            } catch (e: SecurityException) {
                Log.e(TAG, "Contacts permission not granted", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Contacts permission not granted"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create contact", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Failed to create contact: ${e.message}"))
            }
        }.start()
    }

    private fun readContacts(context: Context): JSONArray {
        val contacts = JSONArray()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            val seen = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numberIdx) ?: ""

                // A contact can have multiple numbers; dedupe by (id, number).
                val key = "$id:$number"
                if (!seen.add(key)) continue

                contacts.put(JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("phone", number)
                })
            }
        }

        return contacts
    }

    private fun createContact(context: Context, name: String, phone: String?, email: String?) {
        val ops = ArrayList<android.content.ContentProviderOperation>()

        ops.add(
            android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )

        ops.add(
            android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build()
        )

        if (!phone.isNullOrBlank()) {
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
        }

        if (!email.isNullOrBlank()) {
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.DATA, email)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                    .build()
            )
        }

        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        Log.d(TAG, "Contact created: $name")
    }

    private fun postResponse(requestId: String, hostId: String, result: JSONObject) {
        try {
            val url = URL("${MainActivity.SERVER_URL}/api/device/contacts-response")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val payload = JSONObject().apply {
                put("requestId", requestId)
                put("hostId", hostId)
                put("result", result)
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            Log.d(TAG, "Response posted, status: ${conn.responseCode}")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post response", e)
        }
    }
}
