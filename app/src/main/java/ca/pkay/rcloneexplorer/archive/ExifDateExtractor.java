package ca.pkay.rcloneexplorer.archive;

import androidx.exifinterface.media.ExifInterface;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import ca.pkay.rcloneexplorer.util.FLog;

public class ExifDateExtractor {

    private static final String TAG = "ExifDateExtractor";
    private static final SimpleDateFormat EXIF_FORMAT = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);

    /**
     * Extracts date from a local file using EXIF metadata.
     */
    public static Calendar extractDateFromLocalFile(String localPath) {
        try {
            ExifInterface exif = new ExifInterface(localPath);
            return extractDateFromExif(exif);
        } catch (IOException e) {
            FLog.e(TAG, "extractDateFromLocalFile: error reading EXIF", e);
            return null;
        }
    }

    /**
     * Extracts date from an input stream using EXIF metadata.
     * Useful for remote files where only a partial stream is downloaded.
     */
    public static Calendar extractDateFromStream(InputStream is) {
        try {
            ExifInterface exif = new ExifInterface(is);
            return extractDateFromExif(exif);
        } catch (IOException e) {
            FLog.e(TAG, "extractDateFromStream: error reading EXIF from stream", e);
            return null;
        }
    }

    private static Calendar extractDateFromExif(ExifInterface exif) {
        String dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
        if (dateStr == null) {
            dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED);
        }
        if (dateStr == null) {
            dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME);
        }

        if (dateStr != null) {
            try {
                Date date = EXIF_FORMAT.parse(dateStr);
                if (date != null) {
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(date);
                    return cal;
                }
            } catch (ParseException ignored) {
            }
        }
        return null;
    }
}
