package app.nichez.auth.service;

import app.nichez.common.ApiException;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Turns what the user typed into a validated E.164 number and the region it belongs to. */
@Component
public class PhoneNumberParser {

    public static final String INVALID_PHONE = "INVALID_PHONE";

    /** A parsed, valid number. {@code region} is the ISO region code, for example NG. */
    public record ParsedPhone(String e164, String region) {
    }

    private static final Pattern DIAL_CODE = Pattern.compile("^\\+?[0-9]{1,4}$");
    /** Digits and the separators people type. Letters are refused: libphonenumber would turn "FLOWERS" into digits. */
    private static final Pattern NATIONAL_NUMBER = Pattern.compile("^[0-9 ()\\-.]+$");

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    public ParsedPhone parse(String dialCode, String nationalNumber) {
        String dial = dialCode == null ? "" : dialCode.trim();
        String national = nationalNumber == null ? "" : nationalNumber.trim();
        if (!DIAL_CODE.matcher(dial).matches() || !NATIONAL_NUMBER.matcher(national).matches()) {
            throw invalid();
        }
        int countryCode = Integer.parseInt(dial.replace("+", ""));
        String digits = national.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            throw invalid();
        }
        try {
            PhoneNumber number = util.parse("+" + countryCode + digits, null);
            if (number.getCountryCode() != countryCode || !util.isValidNumber(number)) {
                throw invalid();
            }
            String region = util.getRegionCodeForNumber(number);
            if (region == null) {
                throw invalid();
            }
            return new ParsedPhone(util.format(number, PhoneNumberFormat.E164), region);
        } catch (NumberParseException e) {
            // The exception message can quote the input, so it is not passed on.
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, INVALID_PHONE, "That phone number is not valid.");
    }
}
