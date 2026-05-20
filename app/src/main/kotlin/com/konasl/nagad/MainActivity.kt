package com.konsal.nagad

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ১. রুট লেআউট হিসেবে একটি FrameLayout তৈরি করা (XML এর বিকল্প)
        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ২. WebView তৈরি এবং কনফিগার করা
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            
            // ওয়েবভিউ সেটিংস কনফিগার করা
            settings.apply {
                javaScriptEnabled = true // ইউটিউব চালানোর জন্য অত্যন্ত জরুরি
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            // একই অ্যাপের ভেতর লিংক ওপেন করার জন্য WebViewClient সেট করা
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    progressBar.visibility = View.VISIBLE // পেজ লোড হওয়া শুরু হলে প্রোগ্রেস বার দেখাবে
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    progressBar.visibility = View.GONE // লোড শেষ হলে প্রোগ্রেs বার লুকিয়ে যাবে
                }
            }

            // ভিডিও ফুলস্ক্রিন বা অন্যান্য ক্রোম ফিচারের জন্য WebChromeClient
            webChromeClient = WebChromeClient()
        }

        // ৩. একটি প্রোগ্রেস বার তৈরি করা (পেজ লোড হওয়ার ইন্ডিকেটর)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // স্ক্রিনের একদম মাঝখানে প্রোগ্রেস বারটি পজিশন করা
                gravity = android.view.Gravity.CENTER
            }
            layoutParams = params
            visibility = View.GONE
        }

        // ৪. রুট লেআউটে WebView এবং ProgressBar যোগ করা
        rootLayout.addView(webView)
        rootLayout.addView(progressBar)

        // ৫. অ্যাক্টিভিটিতে রুট ভিউটি সেট করা
        setContentView(rootLayout)

        // ৬. ইউটিউব ইউআরএল লোড করা
        webView.loadUrl("https://www.youtube.com")

        // ৭. ব্যাক বাটন হ্যান্ডেল করা (যাতে অ্যাপ থেকে বের না হয়ে ইউটিউবের আগের পেজে ফিরে যায়)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // যদি পেছনে যাওয়ার মতো কোনো পেজ না থাকে, তবে অ্যাপ বন্ধ হবে
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // অ্যাপ মিনিমাইজ বা ব্যাকগ্রাউন্ডে গেলে যাতে ওয়েবভিউ পজ (Pause) হয়
    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    // অ্যাপ আবার সামনে আসলে ওয়েবভিউ রেজুম (Resume) করা
    override fun onResume() {
        super.onResume()
        webView.onResume()
    }
}
