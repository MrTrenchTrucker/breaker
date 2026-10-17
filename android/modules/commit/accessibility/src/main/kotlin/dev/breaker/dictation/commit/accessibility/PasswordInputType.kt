package dev.breaker.dictation.commit.accessibility

// android.text.InputType.TYPE_MASK_CLASS: the low four bits hold the class.
private const val TYPE_MASK_CLASS: Int = 0xF

// android.text.InputType.TYPE_MASK_VARIATION: the bits from 4 to 11 hold the variation.
private const val TYPE_MASK_VARIATION: Int = 0xFF0

// android.text.InputType.TYPE_CLASS_TEXT
private const val TYPE_CLASS_TEXT: Int = 0x1

// android.text.InputType.TYPE_CLASS_NUMBER
private const val TYPE_CLASS_NUMBER: Int = 0x2

// android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
private const val TYPE_TEXT_VARIATION_PASSWORD: Int = 0x80

// android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Int = 0x90

// android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
private const val TYPE_TEXT_VARIATION_WEB_PASSWORD: Int = 0xE0

// android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
private const val TYPE_NUMBER_VARIATION_PASSWORD: Int = 0x10

/**
 * Whether a raw input type says that a field holds a password, so an insert must refuse it.
 *
 * The adapter reads the platform's password flag first, and this rule only adds to it: a field
 * whose raw input type says password is refused even when the flag is off. This rule refuses
 * every field that may hold a password: a text field with the password, visible password or
 * web password variation, and a number field with the password variation. When in doubt, refuse.
 *
 * Only the class (the low four bits) and the variation (bits 4 to 11) are read. Flags above the
 * variation, such as multi line or no suggestions, do not change the answer. Any other class, any
 * other variation, and a wrong class and variation pair (a text field with the number password
 * variation is a URI field) is not a password.
 */
internal fun isPasswordInputType(inputType: Int): Boolean {
    val inputClass: Int = inputType and TYPE_MASK_CLASS
    val variation: Int = inputType and TYPE_MASK_VARIATION
    if (inputClass == TYPE_CLASS_TEXT) {
        return variation == TYPE_TEXT_VARIATION_PASSWORD ||
            variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
    }
    if (inputClass == TYPE_CLASS_NUMBER) {
        return variation == TYPE_NUMBER_VARIATION_PASSWORD
    }
    return false
}
