package com.example.mirror.webview


import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.mirror.webview.databinding.ActivityMainBinding
import com.example.mirror.webview.web.MirrorWebViewActivity
import com.example.mirror.webview.web.MirrorWebViewActivity.Companion.EXTRA_URL
import com.ffalcon.mercury.android.sdk.core.make3DEffectForSide
import com.ffalcon.mercury.android.sdk.focus.reqFocus
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.util.FixPosFocusTracker
import com.ffalcon.mercury.android.sdk.ui.util.FocusHolder
import com.ffalcon.mercury.android.sdk.ui.util.FocusInfo
import kotlinx.coroutines.launch
import java.security.MessageDigest
import kotlin.jvm.java

class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {
    private var fixPosFocusTracker: FixPosFocusTracker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initFocusTarget()
        initEvent()
    }

    private fun initFocusTarget() {
        val focusHolder = FocusHolder()
        mBindingPair.setLeft {
            focusHolder.addFocusTarget(
                FocusInfo(
                    btn1,
                    eventHandler = { action ->
                        when (action) {
                            is TempleAction.Click -> {
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        MirrorWebViewActivity::class.java
                                    ).putExtra(EXTRA_URL, "https://rayneo.cn/x3pro.html")
                                )
                            }

                            else -> Unit
                        }
                    },
                    focusChangeHandler = { hasFocus ->
                        mBindingPair.updateView {
                            triggerFocus(hasFocus, btn1, mBindingPair.checkIsLeft(this))
                        }
                    }
                ),
                FocusInfo(
                    btn2,
                    eventHandler = { action ->
                        when (action) {
                            is TempleAction.Click -> {
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        MirrorWebViewActivity::class.java
                                    ).putExtra(EXTRA_URL, "https://play.google.com/store/apps")
                                )
                            }

                            else -> Unit
                        }
                    },
                    focusChangeHandler = { hasFocus ->
                        mBindingPair.updateView {
                            triggerFocus(hasFocus, btn2, mBindingPair.checkIsLeft(this))
                        }
                    }
                ),
                FocusInfo(
                    btn3,
                    eventHandler = { action ->
                        when (action) {
                            is TempleAction.Click -> {
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        MirrorWebViewActivity::class.java
                                    ).putExtra(EXTRA_URL, "https://news.ycombinator.com/")
                                )
                            }

                            else -> Unit
                        }
                    },
                    focusChangeHandler = { hasFocus ->
                        mBindingPair.updateView {
                            triggerFocus(hasFocus, btn3, mBindingPair.checkIsLeft(this))
                        }
                    }
                ),
                FocusInfo(
                    btn4,
                    eventHandler = { action ->
                        when (action) {
                            is TempleAction.Click -> {
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        MirrorWebViewActivity::class.java
                                    ).putExtra(EXTRA_URL, "https://github.com/rayneo-develop/MirrorWebView")
                                )
                            }

                            else -> Unit
                        }
                    },
                    focusChangeHandler = { hasFocus ->
                        mBindingPair.updateView {
                            triggerFocus(hasFocus, btn4, mBindingPair.checkIsLeft(this))
                        }
                    }
                ),
                FocusInfo(
                    btn5,
                    eventHandler = { action ->
                        when (action) {
                            is TempleAction.Click -> {
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        MirrorWebViewActivity::class.java
                                    )
                                )
                            }

                            else -> Unit
                        }
                    },
                    focusChangeHandler = { hasFocus ->
                        mBindingPair.updateView {
                            triggerFocus(hasFocus, btn5, mBindingPair.checkIsLeft(this))
                        }
                    }
                ),
                FocusInfo(
                    btn6,
                    eventHandler = { action ->
                        when (action) {
                            is TempleAction.Click -> {
                                finish()
                            }

                            else -> Unit
                        }
                    },
                    focusChangeHandler = { hasFocus ->
                        mBindingPair.updateView {
                            triggerFocus(hasFocus, btn6, mBindingPair.checkIsLeft(this))
                        }
                    }
                )

            )
            focusHolder.currentFocus(mBindingPair.left.btn1)
        }
        fixPosFocusTracker = FixPosFocusTracker(focusHolder).apply {
            focusObj.reqFocus()
        }
    }


    private fun initEvent() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect {
                    when (it) {
                        is TempleAction.DoubleClick -> {
                            finish()
                        }

                        else -> fixPosFocusTracker?.handleFocusTargetEvent(it)
                    }
                }
            }
        }
    }

    private fun triggerFocus(hasFocus: Boolean, view: View, isLeft: Boolean) {
        view.setBackgroundColor(getColor(if (hasFocus) com.ffalcon.mercury.android.sdk.R.color.color_rayneo_theme_0 else R.color.black))
        // 3D effect
        make3DEffectForSide(view, isLeft, hasFocus)
    }
}