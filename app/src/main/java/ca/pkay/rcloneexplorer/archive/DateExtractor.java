package ca.pkay.rcloneexplorer.archive;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DateExtractor {

    // Patterns to look for in filenames
    private static final Pattern[] DATE_PATTERNS = {
            // YYYYMMDD (e.g. 20221013)
            Pattern.compile("(?<!\\d)(\\d{4})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(?!\\d)"),
            // YYYY-MM-DD or YYYY_MM_DD or YYYY.MM.DD
            Pattern.compile("(?<!\\d)(\\d{4})[-_\\.](0[1-9]|1[0-2])[-_\\.](0[1-9]|[12]\\d|3[01])(?!\\d)")
    };

    /**
     * Extracts a date from a filename.
     * @param filename The filename to parse.
     * @return A Calendar object representing the date, or null if no valid date found.
     */
    public static Calendar extractDateFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return null;
        }

        for (Pattern pattern : DATE_PATTERNS) {
            Matcher matcher = pattern.matcher(filename);
            if (matcher.find()) {
                try {
                    int year = Integer.parseInt(matcher.group(1));
                    int month = Integer.parseInt(matcher.group(2)) - 1; // Calendar months are 0-based
                    int day = Integer.parseInt(matcher.group(3));

                    if (isValidDate(year, month, day)) {
                        Calendar cal = Calendar.getInstance();
                        cal.set(year, month, day, 0, 0, 0);
                        cal.set(Calendar.MILLISECOND, 0);
                        return cal;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static boolean isValidDate(int year, int month, int day) {
        if (year < 1900 || year > 2100) {
            return false;
        }
        Calendar cal = Calendar.getInstance();
        cal.setLenient(false);
        cal.set(year, month, day);
        try {
            cal.getTimeInMillis();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
