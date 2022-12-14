package com.revertron.mimir.storage

import android.content.Context
import com.revertron.mimir.ui.Contact
import java.io.*

class Storage(private val context: Context) {

    lateinit var contacts: MutableMap<String, String>

    fun init() {
        contacts = loadMap(context, "contacts") as MutableMap<String, String>
    }

    fun addContact(name: String, address: String): Boolean {
        if (contacts.containsKey(address)) {
            return false
        }
        contacts[address] = name
        saveMap(context, "contacts", contacts as Map<Any, Any>)
        return true
    }

    fun getContactList(): List<Contact> {
        val list = mutableListOf<Contact>()
        contacts.forEach{ (addr, name) ->
            list.add(Contact(0, name, addr, "", 0))
        }
        return list
    }

    private fun saveMap(context: Context, name: String, inputMap: Map<Any, Any>) {
        val fos: FileOutputStream = context.openFileOutput(name, Context.MODE_PRIVATE)
        val os = ObjectOutputStream(fos)
        os.writeObject(inputMap)
        os.close()
        fos.close()
    }

    private fun loadMap(context: Context, name: String): MutableMap<Any, Any> {
        return try {
            val fos: FileInputStream = context.openFileInput(name)
            val os = ObjectInputStream(fos)
            val map: MutableMap<Any, Any> = os.readObject() as MutableMap<Any, Any>
            os.close()
            fos.close()
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun deleteMap(context: Context, name: String): Boolean {
        val file: File = context.getFileStreamPath(name)
        return file.delete()
    }
}