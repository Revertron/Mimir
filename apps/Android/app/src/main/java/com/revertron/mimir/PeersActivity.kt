package com.revertron.mimir

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ui.PeerAdapter

class PeersActivity : AppCompatActivity() {

    companion object {
        const val PREF_PEERS = "peers"
        const val PREF_DEFAULT_PEERS = "defaultPeers"
    }

    private val peers = mutableListOf<String>()
    private lateinit var adapter: PeerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_peers)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val useDefaultSwitch: SwitchCompat = findViewById(R.id.useDefaultSwitch)
        val customPeersContainer: View = findViewById(R.id.customPeersContainer)
        val recycler: RecyclerView = findViewById(R.id.recycler)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
        val btnAdd: ImageButton = findViewById(R.id.btnAdd)

        // Switch listener
        useDefaultSwitch.setOnCheckedChangeListener { _, isChecked ->
            customPeersContainer.visibility = if (isChecked) View.GONE else View.VISIBLE
            val preferences = PreferenceManager.getDefaultSharedPreferences(this)
            preferences.edit().apply {
                putBoolean(PREF_DEFAULT_PEERS, isChecked)
                commit()
            }
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val useDefaultPeers = preferences.getBoolean(PREF_DEFAULT_PEERS, true)
        useDefaultSwitch.isChecked = useDefaultPeers

        val peerList = preferences.getString(PREF_PEERS, "")
        if (peerList != null && peerList.isNotEmpty()) {
            peers.addAll(peerList.lines())
        }

        // RecyclerView
        adapter = PeerAdapter(peers) { position ->
            peers.removeAt(position)
            adapter.notifyItemRemoved(position)
            val preferences = PreferenceManager.getDefaultSharedPreferences(this)
            preferences.edit().apply {
                this.putString(PREF_PEERS, peers.joinToString("\n"))
                commit()
            }
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // Add button
        btnAdd.setOnClickListener { showAddPeerDialog() }
    }

    override fun onDestroy() {
        val intent = Intent(this, ConnectionService::class.java)
            .putExtra("command", "refresh_peer")
        startService(intent)
        super.onDestroy()
    }

    private fun showAddPeerDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.add_peer_dialog, null)
        val peerUrl = view.findViewById<AppCompatEditText>(R.id.peer_url)
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(getString(R.string.add_custom_peer))
            .setView(view)
            .setIcon(R.drawable.ic_plus_network_outline)
            .setPositiveButton(getString(R.string.add_button)) { _, _ ->
                val peer = peerUrl.text.toString().trim()
                if (peer.isNotEmpty() && !peer.contains("\n")) {
                    val peer = peer.trim()
                    peers.add(peer)
                    adapter.notifyItemInserted(peers.size - 1)
                    val preferences = PreferenceManager.getDefaultSharedPreferences(this)
                    preferences.edit().apply {
                        this.putString(PREF_PEERS, peers.joinToString("\n"))
                        commit()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}