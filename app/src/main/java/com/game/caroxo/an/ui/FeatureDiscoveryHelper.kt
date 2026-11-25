package com.game.caroxo.an.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.game.caroxo.an.R
import com.game.caroxo.an.data.PreferencesManager
import com.game.caroxo.an.databinding.LayoutFeatureDiscoveryCardBinding

object FeatureDiscoveryHelper {

    fun showTip(
        container: ViewGroup,
        featureKey: String,
        iconRes: Int,
        stepText: String,
        titleRes: Int,
        descRes: Int,
        prefs: PreferencesManager,
        onDismissed: () -> Unit = {}
    ) {
        if (prefs.isAllDiscoverySkipped() || prefs.isFeatureDiscovered(featureKey)) {
            container.visibility = View.GONE
            return
        }
        container.removeAllViews()
        val binding = LayoutFeatureDiscoveryCardBinding.inflate(
            LayoutInflater.from(container.context),
            container,
            true
        )
        container.visibility = View.VISIBLE
        binding.ivDiscoveryIcon.setImageResource(iconRes)
        binding.tvDiscoveryStep.text = stepText
        binding.tvDiscoveryTitle.setText(titleRes)
        binding.tvDiscoveryDesc.setText(descRes)

        binding.btnDiscoveryAction.setOnClickListener {
            prefs.markFeatureDiscovered(featureKey)
            container.visibility = View.GONE
            onDismissed()
        }

        binding.btnDiscoveryClose.setOnClickListener {
            prefs.markFeatureDiscovered(featureKey)
            container.visibility = View.GONE
            onDismissed()
        }

        binding.btnDiscoverySkipAll.setOnClickListener {
            prefs.setSkipAllDiscovery(true)
            container.visibility = View.GONE
            Toast.makeText(container.context, R.string.discovery_toast_completed, Toast.LENGTH_SHORT).show()
            onDismissed()
        }
    }
}
