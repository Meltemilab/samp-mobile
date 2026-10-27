package com.winlator.cmod.samp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/** Launcher settings: nickname, GTA folder, servers, container id. */
public class SampConfig {
    public static class Server {
        public String name;
        public String host;
        public int port;

        public Server(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        public String address() {
            return host + ":" + port;
        }
    }

    private final SharedPreferences prefs;

    public SampConfig(Context context) {
        prefs = context.getSharedPreferences("samp_launcher", Context.MODE_PRIVATE);
    }

    public String getNickname() { return prefs.getString("nickname", ""); }
    public void setNickname(String v) { prefs.edit().putString("nickname", v).apply(); }

    public String getGtaPath() { return prefs.getString("gta_path", ""); }
    public void setGtaPath(String v) { prefs.edit().putString("gta_path", v).apply(); }

    public int getContainerId() { return prefs.getInt("container_id", 0); }
    public void setContainerId(int v) { prefs.edit().putInt("container_id", v).apply(); }

    /** "auto", "turnip" (Turnip + DXVK) or "virgl" (VirGL + WineD3D). */
    public String getGraphicsMode() { return prefs.getString("graphics_mode", "auto"); }
    public void setGraphicsMode(String v) { prefs.edit().putString("graphics_mode", v).apply(); }

    public int getSelectedServer() { return prefs.getInt("selected_server", -1); }
    public void setSelectedServer(int v) { prefs.edit().putInt("selected_server", v).apply(); }

    /** Favourites added on first run. */
    public static ArrayList<Server> defaultServers() {
        ArrayList<Server> list = new ArrayList<>();
        list.add(new Server("[ENG] Valrise RPG [Android/PC]", "rp.valrisegaming.com", 7777));
        list.add(new Server("Vice City Roleplay", "samp.vc-roleplay.net", 7777));
        return list;
    }

    public ArrayList<Server> getServers() {
        if (!prefs.contains("servers")) {
            ArrayList<Server> defaults = defaultServers();
            setServers(defaults);
            setSelectedServer(0);
            return defaults;
        }
        ArrayList<Server> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("servers", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Server(o.optString("name"), o.optString("host"), o.optInt("port", 7777)));
            }
        }
        catch (JSONException e) {}
        return list;
    }

    public void setServers(ArrayList<Server> servers) {
        JSONArray arr = new JSONArray();
        try {
            for (Server s : servers) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("host", s.host);
                o.put("port", s.port);
                arr.put(o);
            }
        }
        catch (JSONException e) {}
        prefs.edit().putString("servers", arr.toString()).apply();
    }
}
