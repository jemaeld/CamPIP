package com.tvcampip

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {

    /** Primera IPv4 privada (192.168.x.x, 10.x.x.x...) del dispositivo, o null si no hay red. */
    fun localIpv4(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        } catch (_: Exception) {
            return null
        }
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses.toList()) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) return addr.hostAddress
            }
        }
        return null
    }

    fun qrBitmap(text: String, size: Int = 480): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
