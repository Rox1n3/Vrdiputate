package kz.kitdev.analytics;

import java.util.List;
import java.util.Map;

public class ComplaintItem {
    public String id;
    public String fio;
    public String phone;
    public String problem;
    public String address;
    public String lang;
    public long   timestampMillis;
    public String status;          // "processing" | "in_work" | "done" | "rejected"
    public int    complaintNumber; // sequential number e.g. 1 → shown as 0001
    public double lat;
    public double lng;
    /** [{role:"user"|"bot", text:"..."}] */
    public List<Map<String, Object>> messages;

    public ComplaintItem() {}
}
