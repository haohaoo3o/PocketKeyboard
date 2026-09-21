package com.pocketkeyboard.app.hid

/**
 * 蓝牙 HID 传输统一接口（跨模块契约，禁止修改签名）。
 *
 * 所有键盘 / 媒体键 / 鼠标事件均通过本接口下发，具体实现（BLE HID over GATT、
 * 经典蓝牙 HID 通道等）由后续工程师提供，调用方只依赖此接口。
 */
interface HidTransport {

    /** 启动传输（建立连接 / 开启服务）。 */
    fun start()

    /** 停止传输并释放资源。 */
    fun stop()

    /**
     * 发送标准 8 字节引导（boot protocol）键盘报告。
     *
     * @param modifiers 修饰键位图（bit0 LeftCtrl, bit1 LeftShift, bit2 LeftAlt,
     *                  bit3 LeftGUI, bit4 RightCtrl, bit5 RightShift,
     *                  bit6 RightAlt, bit7 RightGUI）
     * @param keyCodes 最多 6 个按键 Usage ID（HID Usage Tables, Keyboard/Keypad Page 0x07），
     *                  空数组表示松开全部按键
     */
    fun sendKeyboardReport(modifiers: Int, keyCodes: ByteArray)

    /**
     * 发送 Consumer Page（0x0C）媒体键，如播放 / 暂停、音量增减。
     *
     * @param usage Consumer Page Usage ID，0 表示松开
     */
    fun sendConsumerUsage(usage: Int)

    /**
     * 触控板相对位移。
     *
     * @param dx 水平位移（正向右）
     * @param dy 垂直位移（正向下）
     */
    fun sendMouseMove(dx: Int, dy: Int)

    /**
     * 滚轮事件。
     *
     * @param dx 水平滚动（正向右）
     * @param dy 垂直滚动（正向下）
     */
    fun sendScroll(dx: Int, dy: Int)

    /**
     * 物理鼠标按键按下 / 松开。
     *
     * @param button 左键或右键
     * @param pressed true 按下，false 松开
     */
    fun sendMouseButton(button: MouseButton, pressed: Boolean)
}

/** 物理鼠标按键。 */
enum class MouseButton { LEFT, RIGHT }
