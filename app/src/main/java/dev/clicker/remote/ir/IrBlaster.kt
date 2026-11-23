package dev.clicker.remote.ir

import android.content.Context
import android.hardware.ConsumerIrManager

/** Sends IR through Android's standard ConsumerIrManager (the RS90 has a built-in IR emitter). */
class IrBlaster(context: Context) {
    private val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val available: Boolean get() = manager?.hasIrEmitter() == true

    fun send(signal: IrSignal) {
        val ir = manager ?: throw IllegalStateException("This device has no IR emitter")
        ir.transmit(signal.frequencyHz, signal.pattern)
    }
}

/** IR codes for the devices this remote knows. */
object IrCodes {
    /** LG TVs: NEC, address 0x04. */
    object LgTv {
        private const val ADDR = 0x04
        val power = Nec.encode(ADDR, 0x08)
        val powerOn = Nec.encode(ADDR, 0xC4)
        val powerOff = Nec.encode(ADDR, 0xC5)
        val volumeUp = Nec.encode(ADDR, 0x02)
        val volumeDown = Nec.encode(ADDR, 0x03)
        val mute = Nec.encode(ADDR, 0x09)
        val input = Nec.encode(ADDR, 0x0B)
        val up = Nec.encode(ADDR, 0x40)
        val down = Nec.encode(ADDR, 0x41)
        val left = Nec.encode(ADDR, 0x07)
        val right = Nec.encode(ADDR, 0x06)
        val ok = Nec.encode(ADDR, 0x44)
        val back = Nec.encode(ADDR, 0x28)
        val home = Nec.encode(ADDR, 0x7C)
        val settings = Nec.encode(ADDR, 0x43)
    }

    /**
     * Denon soundbars (RC-1230 / RC-1236 / DHT-S216 remotes, Flipper-IRDB SoundBars/Denon):
     * Kaseikyo, address "52 54 32 00". Listed as working for the DHT-C210 but not yet confirmed.
     */
    object DenonSoundbar {
        private val ADDR = Kaseikyo.flipperHex("52 54 32 00")
        val power = Kaseikyo.encode(ADDR, 0x83)
        val volumeUp = Kaseikyo.encode(ADDR, 0x84)
        val volumeDown = Kaseikyo.encode(ADDR, 0x85)
        val mute = Kaseikyo.encode(ADDR, 0x86)
    }
}
