package com.github.sumimakito.awesomeqr

import android.graphics.*
import com.github.sumimakito.awesomeqr.option.RenderOption
import com.github.sumimakito.awesomeqr.option.background.BlendBackground
import com.github.sumimakito.awesomeqr.option.background.GifBackground
import com.github.sumimakito.awesomeqr.option.background.StillBackground
import com.github.sumimakito.awesomeqr.util.RectUtils
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.encoder.QRCode
import java.util.*

class AwesomeQrRenderer {
    companion object {
        private const val BYTE_EPT = 0x0.toByte()
        private const val BYTE_DTA = 0x1.toByte()
        private const val BYTE_POS = 0x2.toByte()
        private const val BYTE_AGN = 0x3.toByte()
        private const val BYTE_TMG = 0x4.toByte()
        private const val BYTE_PTC = 0x5.toByte()

        @JvmStatic
        @Throws(Exception::class)
        fun render(renderOptions: RenderOption): RenderResult {
            if (renderOptions.background is GifBackground) {
                val background = renderOptions.background as GifBackground
                if (background.outputFile == null) {
                    throw Exception("Output file has not yet been set. It is required under GIF background mode.")
                }
                val gifPipeline = GifPipeline()
                if (!gifPipeline.init(background.inputFile!!)) {
                    throw Exception("GifPipeline failed to init: " + gifPipeline.errorInfo)
                }
                gifPipeline.clippingRect = background.clippingRectF
                gifPipeline.outputFile = background.outputFile
                var frame: Bitmap?
                var renderedFrame: Bitmap
                var firstRenderedFrame: Bitmap? = null
                frame = gifPipeline.nextFrame()
                while (frame != null) {
                    renderedFrame = renderFrame(renderOptions, frame!!)
                    gifPipeline.pushRendered(renderedFrame)
                    if (firstRenderedFrame == null) {
                        firstRenderedFrame = renderedFrame.copy(Bitmap.Config.ARGB_8888, true)
                    }
                    frame = gifPipeline.nextFrame()
                }
                if (gifPipeline.errorInfo != null) {
                    throw Exception("GifPipeline failed to render frames: " + gifPipeline.errorInfo)
                }
                if (!gifPipeline.postRender()) {
                    throw Exception("GifPipeline failed to do post render works: " + gifPipeline.errorInfo)
                }
                return RenderResult(firstRenderedFrame, background.outputFile, RenderResult.OutputType.GIF)
            } else if (renderOptions.background is BlendBackground && renderOptions.background!!.bitmap != null) {
                val background = renderOptions.background as BlendBackground
                val fallbackBitmap = background.bitmap ?: throw IllegalArgumentException("Background bitmap is null")
                var clippedBackground: Bitmap? = null
                if (background.clippingRect != null) {
                    clippedBackground = Bitmap.createBitmap(
                        fallbackBitmap,
                        Math.round(background.clippingRect!!.left.toFloat()),
                        Math.round(background.clippingRect!!.top.toFloat()),
                        Math.round(background.clippingRect!!.width().toFloat()),
                        Math.round(background.clippingRect!!.height().toFloat())
                    )
                }
                val rendered = renderFrame(renderOptions, clippedBackground ?: fallbackBitmap)
                clippedBackground?.recycle()
                val scaledBoundingRects = scaleImageBoundingRectByClippingRect(fallbackBitmap, renderOptions.size, background.clippingRect)
                val fullRendered = Bitmap.createScaledBitmap(fallbackBitmap, scaledBoundingRects[0].width(), scaledBoundingRects[0].height(), true)
                val fullCanvas = Canvas(fullRendered)
                val paint = Paint()
                paint.isAntiAlias = true
                paint.color = renderOptions.color.background
                paint.isFilterBitmap = true
                fullCanvas.drawBitmap(rendered, Rect(0, 0, rendered.width, rendered.height), scaledBoundingRects[1], paint)
                return RenderResult(fullRendered, null, RenderResult.OutputType.Blend)
            } else if (renderOptions.background is StillBackground) {
                val background = renderOptions.background as StillBackground
                val fallbackBitmap = background.bitmap ?: throw IllegalArgumentException("Background bitmap is null")
                var clippedBackground: Bitmap? = null
                if (background.clippingRect != null) {
                    clippedBackground = Bitmap.createBitmap(
                        fallbackBitmap,
                        Math.round(background.clippingRect!!.left.toFloat()),
                        Math.round(background.clippingRect!!.top.toFloat()),
                        Math.round(background.clippingRect!!.width().toFloat()),
                        Math.round(background.clippingRect!!.height().toFloat())
                    )
                }
                val rendered = renderFrame(renderOptions, clippedBackground ?: fallbackBitmap)
                clippedBackground?.recycle()
                return RenderResult(rendered, null, RenderResult.OutputType.Still)
            } else {
                return RenderResult(renderFrame(renderOptions, null), null, RenderResult.OutputType.Still)
            }
        }

        @JvmStatic
        fun renderAsync(renderOptions: RenderOption, resultCallback: ((RenderResult) -> Unit)?, errorCallback: ((Exception) -> Unit)?) {
            Thread {
                try {
                    val renderResult = render(renderOptions)
                    resultCallback?.invoke(renderResult)
                } catch (e: Exception) {
                    errorCallback?.invoke(e)
                }
            }.start()
        }

        @Throws(Exception::class)
        private fun renderFrame(renderOptions: RenderOption, backgroundFrame: Bitmap?): Bitmap {
            var backgroundFrameTemp = backgroundFrame
            if (renderOptions.content.isEmpty()) {
                throw IllegalArgumentException("Error: content is empty.")
            }
            if (renderOptions.size < 0 || renderOptions.borderWidth < 0 || renderOptions.size - 2 * renderOptions.borderWidth <= 0) {
                throw IllegalArgumentException("Invalid size or borderWidth.")
            }

            val byteMatrix = getByteMatrix(renderOptions.content, renderOptions.ecl)
                ?: throw NullPointerException("ByteMatrix based on content is null.")

            if (renderOptions.patternScale <= 0 || renderOptions.patternScale > 1) {
                throw IllegalArgumentException("Illegal pattern scale.")
            }

            if (renderOptions.logo != null && renderOptions.logo!!.bitmap != null) {
                val logo = renderOptions.logo!!
                if (logo.scale <= 0 || logo.scale > 0.5 ||
                    logo.borderWidth < 0 || logo.borderWidth * 2 >= renderOptions.size ||
                    logo.borderRadius < 0
                ) throw IllegalArgumentException("Invalid logo settings.")
            }

            if (backgroundFrameTemp == null &&
                (renderOptions.background is StillBackground || renderOptions.background is BlendBackground)
            ) {
                backgroundFrameTemp = renderOptions.background?.bitmap
            }

            val innerRenderedSize = renderOptions.size - 2 * renderOptions.borderWidth
            val nCount = byteMatrix.width
            val nSize = Math.round(innerRenderedSize.toFloat() / nCount)
            val unscaledInnerRenderSize = nSize * nCount
            val unscaledFullRenderSize = unscaledInnerRenderSize + 2 * renderOptions.borderWidth

            val backgroundDrawingRect = Rect(
                if (!renderOptions.clearBorder) 0 else renderOptions.borderWidth,
                if (!renderOptions.clearBorder) 0 else renderOptions.borderWidth,
                unscaledFullRenderSize - renderOptions.borderWidth * if (renderOptions.clearBorder) 1 else 0,
                unscaledFullRenderSize - renderOptions.borderWidth * if (renderOptions.clearBorder) 1 else 0
            )

            val unscaledFullRenderedBitmap = Bitmap.createBitmap(unscaledFullRenderSize, unscaledFullRenderSize, Bitmap.Config.ARGB_8888)

            if (renderOptions.color.auto && backgroundFrame != null) {
                renderOptions.color.light = -0x1
                renderOptions.color.dark = getDominantColor(backgroundFrame)
            }

            val paint = Paint().apply { isAntiAlias = true }
            val paintBackground = Paint().apply {
                isAntiAlias = true
                color = renderOptions.color.background
                style = Paint.Style.FILL
            }
            val paintDark = Paint().apply {
                color = renderOptions.color.dark
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            val paintLight = Paint().apply {
                color = renderOptions.color.light
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            val paintProtector = Paint().apply {
                color = Color.argb(120, 255, 255, 255)
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            val unscaledCanvas = Canvas(unscaledFullRenderedBitmap)
            unscaledCanvas.drawColor(Color.WHITE)
            unscaledCanvas.drawRect(
                backgroundDrawingRect,
                paintBackground
            )

            if (backgroundFrame != null && renderOptions.background != null) {
                paint.alpha = Math.round(255 * renderOptions.background!!.alpha)
                unscaledCanvas.drawBitmap(backgroundFrame, null, backgroundDrawingRect, paint)
            }
            paint.alpha = 255

            for (row in 0 until byteMatrix.height) {
                for (col in 0 until byteMatrix.width) {
                    val x = renderOptions.borderWidth + col * nSize
                    val y = renderOptions.borderWidth + row * nSize
                    val value = byteMatrix.get(col, row)
                    val paintToUse = when (value) {
                        BYTE_AGN, BYTE_POS, BYTE_TMG -> paintDark
                        BYTE_PTC -> paintProtector
                        BYTE_EPT -> if (renderOptions.roundedPatterns) {
                            unscaledCanvas.drawCircle(
                                x + nSize / 2f,
                                y + nSize / 2f,
                                renderOptions.patternScale * nSize / 2f,
                                paintLight
                            )
                            continue
                        } else paintLight
                        BYTE_DTA -> if (renderOptions.roundedPatterns) {
                            unscaledCanvas.drawCircle(
                                x + nSize / 2f,
                                y + nSize / 2f,
                                renderOptions.patternScale * nSize / 2f,
                                paintDark
                            )
                            continue
                        } else paintDark
                        else -> null
                    }
                    paintToUse?.let {
                        unscaledCanvas.drawRect(x.toFloat(), y.toFloat(), (x + nSize).toFloat(), (y + nSize).toFloat(), it)
                    }
                }
            }

            if (renderOptions.logo?.bitmap != null) {
                val logo = renderOptions.logo!!
                val logoScaledSize = (unscaledInnerRenderSize * logo.scale).toInt()
                val logoScaled = Bitmap.createScaledBitmap(logo.bitmap!!, logoScaledSize, logoScaledSize, true)
                val logoOpt = Bitmap.createBitmap(logoScaled.width, logoScaled.height, Bitmap.Config.ARGB_8888)
                val logoCanvas = Canvas(logoOpt)
                val logoRect = Rect(0, 0, logoScaled.width, logoScaled.height)
                val logoPaint = Paint().apply { isAntiAlias = true }
                val logoRectF = RectF(logoRect)
                logoPaint.color = -0x1
                logoCanvas.drawARGB(0, 0, 0, 0)
                logoCanvas.drawRoundRect(logoRectF, logo.borderRadius.toFloat(), logo.borderRadius.toFloat(), logoPaint)
                logoPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                logoCanvas.drawBitmap(logoScaled, logoRect, logoRect, logoPaint)
                logoPaint.color = renderOptions.color.light
                logoPaint.style = Paint.Style.STROKE
                logoPaint.strokeWidth = logo.borderWidth.toFloat()
                logoCanvas.drawRoundRect(logoRectF, logo.borderRadius.toFloat(), logo.borderRadius.toFloat(), logoPaint)
                unscaledCanvas.drawBitmap(logoOpt,
                    (0.5 * (unscaledFullRenderedBitmap.width - logoOpt.width)).toFloat(),
                    (0.5 * (unscaledFullRenderedBitmap.height - logoOpt.height)).toFloat(),
                    paint)
            }

            val renderedScaledBitmap = Bitmap.createBitmap(renderOptions.size, renderOptions.size, Bitmap.Config.ARGB_8888)
            val scaledCanvas = Canvas(renderedScaledBitmap)
            scaledCanvas.drawBitmap(unscaledFullRenderedBitmap, null, Rect(0, 0, renderedScaledBitmap.width, renderedScaledBitmap.height), paint)

            var renderedResultBitmap: Bitmap = renderedScaledBitmap

            if (renderOptions.background is BlendBackground) {
                renderedResultBitmap = Bitmap.createBitmap(renderedScaledBitmap.width, renderedScaledBitmap.height, Bitmap.Config.ARGB_8888)
                val finalRenderedCanvas = Canvas(renderedResultBitmap)
                val finalClippingRect = Rect(0, 0, renderedScaledBitmap.width, renderedScaledBitmap.height)
                val finalClippingRectF = RectF(finalClippingRect)
                val finalClippingPaint = Paint().apply {
                    isAntiAlias = true
                    color = -0x1
                    style = Paint.Style.FILL
                }
                finalRenderedCanvas.drawARGB(0, 0, 0, 0)
                finalRenderedCanvas.drawRoundRect(finalClippingRectF, (renderOptions.background as BlendBackground).borderRadius.toFloat(), (renderOptions.background as BlendBackground).borderRadius.toFloat(), finalClippingPaint)
                finalClippingPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                finalRenderedCanvas.drawBitmap(renderedScaledBitmap, null, finalClippingRect, finalClippingPaint)
                renderedScaledBitmap.recycle()
            }

            unscaledFullRenderedBitmap.recycle()
            return renderedResultBitmap
        }

        // Other helper functions remain unchanged...
    }
}
