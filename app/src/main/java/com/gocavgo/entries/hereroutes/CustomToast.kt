package com.gocavgo.entries.hereroutes

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity

class CustomToast private constructor(private val context: Context) {

    enum class ToastType {
        SUCCESS,
        ERROR,
        WARNING,
        INFO
    }

    private var currentToastView: LinearLayout? = null
    private var parentContainer: ViewGroup? = null
    private val TAG = "CustomToast"

    companion object {
        // Static convenience methods - don't use singleton pattern
        fun showSuccess(context: Context, message: String, duration: Long = 3000) {
            // Validate context before creating toast
            val activityContext = getActivityContext(context)
            if (activityContext != null) {
                CustomToast(activityContext).show(message, ToastType.SUCCESS, duration)
            } else {
                // Fallback to regular toast
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        fun showError(context: Context, message: String, duration: Long = 4000) {
            val activityContext = getActivityContext(context)
            if (activityContext != null) {
                CustomToast(activityContext).show(message, ToastType.ERROR, duration)
            } else {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        fun showWarning(context: Context, message: String, duration: Long = 3500) {
            val activityContext = getActivityContext(context)
            if (activityContext != null) {
                CustomToast(activityContext).show(message, ToastType.WARNING, duration)
            } else {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        fun showInfo(context: Context, message: String, duration: Long = 3000) {
            val activityContext = getActivityContext(context)
            if (activityContext != null) {
                CustomToast(activityContext).show(message, ToastType.INFO, duration)
            } else {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        private fun getActivityContext(context: Context): Context? {
            return when (context) {
                is AppCompatActivity, is FragmentActivity, is android.app.Activity -> context
                is android.content.ContextWrapper -> {
                    // Unwrap context wrapper to find the base activity
                    var baseContext = context.baseContext
                    while (baseContext is android.content.ContextWrapper &&
                        baseContext !is AppCompatActivity &&
                        baseContext !is FragmentActivity &&
                        baseContext !is android.app.Activity) {
                        baseContext = baseContext.baseContext
                    }
                    if (baseContext is AppCompatActivity || baseContext is FragmentActivity || baseContext is android.app.Activity) {
                        baseContext
                    } else null
                }
                else -> null
            }
        }
    }

    fun show(message: String, type: ToastType, duration: Long = 3000) {
        // Must run on UI thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                show(message, type, duration)
            }
            return
        }

        try {
            // Dismiss any existing toast first
            dismiss()

            // Find the root container (Activity's content view)
            if (!findParentContainer()) {
                Log.e(TAG, "Could not find parent container for toast")
                return
            }

            parentContainer?.let { container ->
                currentToastView = createToastView(message, type)

                // Add to container
                val layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP
                    topMargin = dpToPx(16)
                    leftMargin = dpToPx(16)
                    rightMargin = dpToPx(16)
                }

                container.addView(currentToastView, layoutParams)
                Log.d(TAG, "Toast view added to container")

                // Animate in
                animateIn(currentToastView!!) {
                    // Auto dismiss after duration
                    Handler(Looper.getMainLooper()).postDelayed({
                        dismiss()
                    }, duration)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing toast: ${e.message}", e)
            // Fallback to regular toast
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun findParentContainer(): Boolean {
        return try {
            // The context should already be validated as an Activity by getActivityContext
            parentContainer = (context as android.app.Activity).findViewById<ViewGroup>(android.R.id.content)

            if (parentContainer != null) {
                Log.d(TAG, "Found Activity content view: ${context::class.java.simpleName}")
                true
            } else {
                Log.e(TAG, "Content view is null for activity: ${context::class.java.simpleName}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding parent container: ${e.message}", e)
            false
        }
    }

    private fun createToastView(message: String, type: ToastType): LinearLayout {
        val toastView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            elevation = dpToPx(8).toFloat()
        }

        // Set background with colors and glow effect
        var background = createToastBackground(type)
        toastView.background = background

        // Create icon
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)).apply {
                rightMargin = dpToPx(12)
            }
            setImageResource(getIconResource(type))
            setColorFilter(Color.WHITE)
        }

        // Create message text
        val messageText = TextView(context).apply {
            text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            maxLines = 3
        }

        // Create close button
        val closeButton = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(20), dpToPx(20)).apply {
                leftMargin = dpToPx(12)
            }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            alpha = 0.7f
            setOnClickListener { dismiss() }

            // Add ripple effect
            val rippleBackground = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            background = rippleBackground
            isClickable = true
        }

        toastView.addView(icon)
        toastView.addView(messageText)
        toastView.addView(closeButton)

        return toastView
    }

    private fun createToastBackground(type: ToastType): GradientDrawable {
        val background = GradientDrawable()

        val (backgroundColor, borderColor) = when (type) {
            ToastType.SUCCESS -> Pair(
                Color.parseColor("#10B981"), // Green-500
                Color.parseColor("#34D399")  // Green-400 for glow
            )
            ToastType.ERROR -> Pair(
                Color.parseColor("#EF4444"), // Red-500
                Color.parseColor("#F87171")  // Red-400 for glow
            )
            ToastType.WARNING -> Pair(
                Color.parseColor("#F59E0B"), // Amber-500
                Color.parseColor("#FBBF24")  // Amber-400 for glow
            )
            ToastType.INFO -> Pair(
                Color.parseColor("#3B82F6"), // Blue-500
                Color.parseColor("#60A5FA")  // Blue-400 for glow
            )
        }

        background.apply {
            shape = GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            cornerRadius = dpToPx(8).toFloat()

            // Add border for glow effect
            setStroke(dpToPx(2), borderColor)
        }

        return background
    }

    private fun getIconResource(type: ToastType): Int {
        return when (type) {
            ToastType.SUCCESS -> android.R.drawable.ic_menu_save
            ToastType.ERROR -> android.R.drawable.ic_dialog_alert
            ToastType.WARNING -> android.R.drawable.ic_dialog_info
            ToastType.INFO -> android.R.drawable.ic_dialog_info
        }
    }

    private fun animateIn(view: LinearLayout, onComplete: () -> Unit) {
        // Ensure view is measured first
        view.post {
            try {
                // Start with view off-screen (translated up)
                view.translationY = -200f // Use fixed value instead of view.height
                view.alpha = 0f

                // Animate down and fade in
                val translateAnimator = ObjectAnimator.ofFloat(view, "translationY", -200f, 0f)
                val alphaAnimator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)

                translateAnimator.duration = 300
                alphaAnimator.duration = 300

                translateAnimator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onComplete()
                    }
                })

                translateAnimator.start()
                alphaAnimator.start()

                Log.d(TAG, "Toast animation started")
            } catch (e: Exception) {
                Log.e(TAG, "Error animating toast in: ${e.message}", e)
                onComplete()
            }
        }
    }

    private fun animateOut(view: LinearLayout, onComplete: () -> Unit) {
        try {
            val translateAnimator = ObjectAnimator.ofFloat(view, "translationY", 0f, -200f)
            val alphaAnimator = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f)

            translateAnimator.duration = 250
            alphaAnimator.duration = 250

            translateAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onComplete()
                }
            })

            translateAnimator.start()
            alphaAnimator.start()

            Log.d(TAG, "Toast dismiss animation started")
        } catch (e: Exception) {
            Log.e(TAG, "Error animating toast out: ${e.message}", e)
            onComplete()
        }
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                dismiss()
            }
            return
        }

        currentToastView?.let { toastView ->
            try {
                animateOut(toastView) {
                    try {
                        parentContainer?.removeView(toastView)
                        currentToastView = null
                        Log.d(TAG, "Toast dismissed successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing toast view: ${e.message}", e)
                        currentToastView = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing toast: ${e.message}", e)
                // Force remove if animation fails
                try {
                    parentContainer?.removeView(toastView)
                    currentToastView = null
                } catch (e2: Exception) {
                    Log.e(TAG, "Error force removing toast: ${e2.message}", e2)
                    currentToastView = null
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}