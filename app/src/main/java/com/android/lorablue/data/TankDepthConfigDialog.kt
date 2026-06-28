package com.android.lorablue.data

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Small dialog opened from the gear icon next to "Water Level" on each
 * card. Lets the user type the tank/cistern's total depth (the
 * sensor-to-bottom distance), which is required to convert the raw TOF
 * sensor distance into a water column height + fill percentage.
 *
 * Not a DialogFragment (unlike MqttConfigDialog) because it needs no
 * lifecycle persistence across rotation — it's a quick one-field prompt
 * triggered directly from a click listener in MainActivity, same pattern
 * as a plain AlertDialog.Builder usage.
 */
object TankDepthConfigDialog {

    /**
     * @param deviceId    DEVICE_ID_CISTERN or DEVICE_ID_TANK
     * @param deviceLabel "Cistern" or "Tank" — used in dialog title/hint
     * @param onSaved     Invoked with the new total depth (meters) right after saving
     */
    fun show(
        context: Context,
        deviceId: Int,
        deviceLabel: String,
        onSaved: (Double) -> Unit
    ) {
        val store = TankDepthConfigStore(context)
        val current = store.getTotalDepth(deviceId)

        val dp = context.resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val message = TextView(context).apply {
            text = "Distance from the sensor (at the top) to the bottom of the $deviceLabel, in meters."
            setPadding(0, 0, 0, (8 * dp).toInt())
        }

        val input = EditText(context).apply {
            hint = "e.g. 2.50"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            current?.let { setText(String.format("%.2f", it)) }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(message)
            addView(input)
        }

        AlertDialog.Builder(context)
            .setTitle("Total Depth — $deviceLabel")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim().replace(",", ".")
                val value = text.toDoubleOrNull()

                if (value == null || value <= 0.0) {
                    Toast.makeText(
                        context,
                        "Enter a numeric value greater than zero",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                store.setTotalDepth(deviceId, value)
                onSaved(value)
                Toast.makeText(
                    context,
                    "$deviceLabel depth saved: ${String.format("%.2f", value)} m",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}