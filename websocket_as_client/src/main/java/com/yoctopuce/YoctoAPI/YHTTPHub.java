/*********************************************************************
 * $Id: YHTTPHub.java 22516 2015-12-23 18:27:24Z seb $
 *
 * Internal YHTTPHUB object
 *
 * - - - - - - - - - License information: - - - - - - - - -
 *
 * Copyright (C) 2011 and beyond by Yoctopuce Sarl, Switzerland.
 *
 * Yoctopuce Sarl (hereafter Licensor) grants to you a perpetual
 * non-exclusive license to use, modify, copy and integrate this
 * file into your software for the sole purpose of interfacing
 * with Yoctopuce products.
 *
 * You may reproduce and distribute copies of this file in
 * source or object form, as long as the sole purpose of this
 * code is to interface with Yoctopuce products. You must retain
 * this notice in the distributed source file.
 *
 * You should refer to Yoctopuce General Terms and Conditions
 * for additional information regarding your rights and
 * obligations.
 *
 * THE SOFTWARE AND DOCUMENTATION ARE PROVIDED 'AS IS' WITHOUT
 * WARRANTY OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING
 * WITHOUT LIMITATION, ANY WARRANTY OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE, TITLE AND NON-INFRINGEMENT. IN NO
 * EVENT SHALL LICENSOR BE LIABLE FOR ANY INCIDENTAL, SPECIAL,
 * INDIRECT OR CONSEQUENTIAL DAMAGES, LOST PROFITS OR LOST DATA,
 * COST OF PROCUREMENT OF SUBSTITUTE GOODS, TECHNOLOGY OR
 * SERVICES, ANY CLAIMS BY THIRD PARTIES (INCLUDING BUT NOT
 * LIMITED TO ANY DEFENSE THEREOF), ANY CLAIMS FOR INDEMNITY OR
 * CONTRIBUTION, OR OTHER SIMILAR COSTS, WHETHER ASSERTED ON THE
 * BASIS OF CONTRACT, TORT (INCLUDING NEGLIGENCE), BREACH OF
 * WARRANTY, OR OTHERWISE.
 *********************************************************************/
package com.yoctopuce.YoctoAPI;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.websocket.Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

class YHTTPHub extends YGenericHub
{

    private final Session _callbackSession;
    private NotificationHandler _notificationHandler;
    private Thread _thread;
    final HTTPParams _http_params;
    private String _http_realm = "";
    private String _nounce = "";
    private String _serial = "";
    private int _nounce_count = 0;
    private String _ha1 = "";
    private String _opaque = "";
    private Random _randGen = new Random();
    private MessageDigest mdigest;
    private int _authRetryCount = 0;
    boolean _writeProtected = false; // fixme move authentication to TCPNotifcaitonHandler

    private final Object _authLock = new Object();


    boolean needRetryWithAuth()
    {
        synchronized (_authLock) {
            return !(_http_params.geUser().length() == 0 || _http_params.getPass().length() == 0) && _authRetryCount++ <= 3;
        }
    }

    void authSucceded()
    {
        synchronized (_authLock) {
            _authRetryCount = 0;
        }
    }

    // Update the hub internal variables according
    // to a received header with WWW-Authenticate
    void parseWWWAuthenticate(String header)
    {
        synchronized (_authLock) {
            int pos = header.indexOf("\r\nWWW-Authenticate:");
            if (pos == -1) return;
            header = header.substring(pos + 19);
            int eol = header.indexOf('\r');
            if (eol >= 0) {
                header = header.substring(0, eol);
            }
            _http_realm = "";
            _nounce = "";
            _opaque = "";
            _nounce_count = 0;

            String tags[] = header.split(" ");
            for (String tag : tags) {
                String parts[] = tag.split("[=\",]");
                String name, value;
                if (parts.length == 2) {
                    name = parts[0];
                    value = parts[1];
                } else if (parts.length == 3) {
                    name = parts[0];
                    value = parts[2];
                } else {
                    continue;
                }
                switch (name) {
                    case "realm":
                        _http_realm = value;
                        break;
                    case "nonce":
                        _nounce = value;
                        break;
                    case "opaque":
                        _opaque = value;
                        break;
                }
            }

            String plaintext = _http_params.geUser() + ":" + _http_realm + ":" + _http_params.getPass();
            mdigest.reset();
            mdigest.update(plaintext.getBytes());
            byte[] digest = this.mdigest.digest();
            _ha1 = YAPIContext._bytesToHexStr(digest, 0, digest.length);
        }
    }


    // Return an Authorization header for a given request
    String getAuthorization(String request) throws YAPI_Exception
    {
        synchronized (_authLock) {
            if (_http_params.geUser().length() == 0 || _http_realm.length() == 0)
                return "";
            _nounce_count++;
            int pos = request.indexOf(' ');
            String method = request.substring(0, pos);
            int enduri = request.indexOf(' ', pos + 1);
            if (enduri < 0)
                enduri = request.length();
            String uri = request.substring(pos + 1, enduri);
            String nc = String.format("%08x", _nounce_count);
            String cnonce = String.format("%08x", _randGen.nextInt());

            String plaintext = method + ":" + uri;
            mdigest.reset();
            mdigest.update(plaintext.getBytes());
            byte[] digest = this.mdigest.digest();
            String ha2 = YAPIContext._bytesToHexStr(digest, 0, digest.length);
            plaintext = _ha1 + ":" + _nounce + ":" + nc + ":" + cnonce + ":auth:" + ha2;
            this.mdigest.reset();
            this.mdigest.update(plaintext.getBytes());
            digest = this.mdigest.digest();
            String response = YAPIContext._bytesToHexStr(digest, 0, digest.length);
            return String.format(
                    "Authorization: Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", qop=auth, nc=%s, cnonce=\"%s\", response=\"%s\", opaque=\"%s\"\r\n",
                    _http_params.geUser(), _http_realm, _nounce, uri, nc, cnonce, response, _opaque);
        }
    }


    YHTTPHub(YAPIContext yctx, int idx, HTTPParams httpParams, boolean reportConnnectionLost, Session session) throws YAPI_Exception
    {
        super(yctx, idx, reportConnnectionLost);
        _http_params = httpParams;
        _callbackSession = session;
        try {
            mdigest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException ex) {
            throw new YAPI_Exception(YAPI.NOT_SUPPORTED, "No MD5 provider");
        }
    }


    @Override
    synchronized void startNotifications() throws YAPI_Exception
    {
        if (_notificationHandler != null) {
            throw new YAPI_Exception(YAPI.INVALID_ARGUMENT, "notification already started");
        }
        if (_http_params.isWebSocket()) {
            _notificationHandler = new WSNotificationHandler(this, _callbackSession);
        } else {
            _notificationHandler = new TCPNotificationHandler(this);
        }
        _thread = new Thread(_notificationHandler, "Notification handler for " + getHost());
        _thread.start();
    }

    @Override
    synchronized void stopNotifications()
    {
        if (_notificationHandler != null) {
            try {
                boolean requestsUnfinished = _notificationHandler.waitAndFreeAsyncTasks(yHTTPRequest.MAX_REQUEST_MS);
                if (requestsUnfinished) {
                    _yctx._Log(String.format("Stop hub %s before all async request has ended", getHost()));
                }
                _thread.interrupt();
                _thread.join(10000);
            } catch (InterruptedException e) {
                _thread = null;
            }
            _notificationHandler = null;
        }
    }

    @Override
    synchronized void release()
    {
    }

    @Override
    String getRootUrl()
    {
        return _http_params.getUrl();
    }

    @Override
    boolean isSameRootUrl(String url)
    {
        HTTPParams params = new HTTPParams(url);
        return params.getUrl().equals(_http_params.getUrl());
    }


    @Override
    synchronized void updateDeviceList(boolean forceupdate) throws YAPI_Exception, InterruptedException
    {

        long now = YAPI.GetTickCount();
        if (forceupdate) {
            _devListExpires = 0;
        }
        if (_devListExpires > now) {
            return;
        }
        if (!_notificationHandler.isConnected()) {
            if (_reportConnnectionLost) {
                throw new YAPI_Exception(YAPI.TIMEOUT, "hub " + this._http_params.getUrl() + " is not reachable");
            } else {
                return;
            }
        }

        String json_data;
        try {
            json_data = new String(_notificationHandler.hubRequestSync("GET /api.json", null, yHTTPRequest.YIO_DEFAULT_TCP_TIMEOUT), StandardCharsets.ISO_8859_1);
        } catch (YAPI_Exception ex) {
            if (_reportConnnectionLost) {
                throw ex;
            }
            return;
        }

        HashMap<String, ArrayList<YPEntry>> yellowPages = new HashMap<>();
        ArrayList<WPEntry> whitePages = new ArrayList<>();

        JSONObject loadval;
        try

        {
            loadval = new JSONObject(json_data);
            if (!loadval.has("services") || !loadval.getJSONObject("services").has("whitePages")) {
                throw new YAPI_Exception(YAPI.INVALID_ARGUMENT, "Device "
                        + _http_params.getHost() + " is not a hub");
            }
            _serial = loadval.getJSONObject("module").getString("serialNumber");
            JSONArray whitePages_json = loadval.getJSONObject("services").getJSONArray("whitePages");
            JSONObject yellowPages_json = loadval.getJSONObject("services").getJSONObject("yellowPages");
            if (loadval.has("network")) {
                String adminpass = loadval.getJSONObject("network").getString("adminPassword");
                _writeProtected = adminpass.length() > 0;
            }
            // Reindex all functions from yellow pages
            //HashMap<String, Boolean> refresh = new HashMap<String, Boolean>();
            Iterator<?> keys = yellowPages_json.keys();
            while (keys.hasNext()) {
                String classname = keys.next().toString();
                JSONArray yprecs_json = yellowPages_json.getJSONArray(classname);
                ArrayList<YPEntry> yprecs_arr = new ArrayList<>(
                        yprecs_json.length());
                for (int i = 0; i < yprecs_json.length(); i++) {
                    YPEntry yprec = new YPEntry(yprecs_json.getJSONObject(i));
                    yprecs_arr.add(yprec);
                }
                yellowPages.put(classname, yprecs_arr);
            }

            _serialByYdx.clear();
            // Reindex all devices from white pages
            for (int i = 0; i < whitePages_json.length(); i++) {
                JSONObject jsonObject = whitePages_json.getJSONObject(i);
                WPEntry devinfo = new WPEntry(jsonObject);
                int index = jsonObject.getInt("index");
                _serialByYdx.put(index, devinfo.getSerialNumber());
                whitePages.add(devinfo);
            }
        } catch (
                JSONException e
                )

        {
            throw new YAPI_Exception(YAPI.IO_ERROR,
                    "Request failed, could not parse API result for "
                            + _http_params.getHost(), e);
        }

        updateFromWpAndYp(whitePages, yellowPages);

        // reset device list cache timeout for this hub
        now = YAPI.GetTickCount();
        _devListExpires = now + _devListValidity;
    }

    @Override
    ArrayList<String> firmwareUpdate(String serial, YFirmwareFile firmware, byte[] settings, UpdateProgress progress) throws YAPI_Exception, InterruptedException
    {
        boolean use_self_flash = false;
        String baseurl = "";
        boolean need_reboot = true;
        // todo: update code to use WS
        yHTTPRequest req = new yHTTPRequest(this, "hubFUpdate" + serial);
        if (serial.equals(_serial) && !_serial.startsWith("VIRTHUB")) {
            use_self_flash = true;
        } else {
            // check if subdevice support self flashing
            try {
                req.RequestSync("GET /bySerial/" + serial + "/flash.json?a=state", null, yHTTPRequest.YIO_DEFAULT_TCP_TIMEOUT);
                baseurl = "/bySerial/" + serial;
                use_self_flash = true;
            } catch (YAPI_Exception ignored) {
            }
        }
        //5% -> 10%
        progress.firmware_progress(5, "Enter in bootloader");
        ArrayList<String> bootloaders = getBootloaders();
        if (bootloaders.size() >= 4) {
            throw new YAPI_Exception(YAPI.IO_ERROR, "Too many devices in update mode");
        }
        boolean is_shield = serial.startsWith("YHUBSHL1");
        for (String bl : bootloaders) {
            if (bl.equals(serial)) {
                need_reboot = false;
            } else if (is_shield) {
                if (bl.startsWith("YHUBSHL1")) {
                    throw new YAPI_Exception(YAPI.IO_ERROR, "Only one YoctoHub-Shield is allowed in update mode");
                }
            }
        }
        if (!use_self_flash && need_reboot) {
            // reboot subdevice
            req.RequestSync("GET /bySerial/" + serial + "/api/module/rebootCountdown?rebootCountdown=-1", null, yHTTPRequest.YIO_DEFAULT_TCP_TIMEOUT);
        }
        //10% -> 40%
        progress.firmware_progress(10, "Send firmware to bootloader");
        byte[] head_body = YDevice.formatHTTPUpload("firmware", firmware.getData());
        req.RequestSync("POST " + baseurl + "/upload.html", head_body, 0);
        //check firmware upload result
        byte[] bytes = req.RequestSync("GET " + baseurl + "/flash.json?a=state", null, yHTTPRequest.YIO_DEFAULT_TCP_TIMEOUT);
        String uploadresstr = new String(bytes);
        try {
            JSONObject uploadres = new JSONObject(uploadresstr);
            if (!uploadres.getString("state").equals("valid")) {
                throw new YAPI_Exception(YAPI.IO_ERROR, "Upload of firmware failed: invalid firmware(" + uploadres.getString("state") + ")");
            }
            if (uploadres.getInt("progress") != 100) {
                throw new YAPI_Exception(YAPI.IO_ERROR, "Upload of firmware failed: incomplete upload");
            }
        } catch (JSONException ex) {
            throw new YAPI_Exception(YAPI.IO_ERROR, "invalid json response :" + ex.getLocalizedMessage());
        }
        if (use_self_flash) {
            progress.firmware_progress(20, "Upload startupConf.json");
            head_body = YDevice.formatHTTPUpload("startupConf.json", settings);
            req.RequestSync("POST " + baseurl + "/upload.html", head_body, yHTTPRequest.YIO_DEFAULT_TCP_TIMEOUT);
            progress.firmware_progress(20, "Upload firmwareConf");
            head_body = YDevice.formatHTTPUpload("firmwareConf", settings);
            req.RequestSync("POST " + baseurl + "/upload.html", head_body, yHTTPRequest.YIO_DEFAULT_TCP_TIMEOUT);
        }

        //40%-> 80%
        if (use_self_flash) {
            progress.firmware_progress(40, "Flash firmware");
            // the hub itself -> reboot in autoflash mode
            req.RequestSync("GET " + baseurl + "/api/module/rebootCountdown?rebootCountdown=-1003", null, yHTTPRequest.YIO_DEFAULT_TCP_TIMEOUT);
            Thread.sleep(7000);
        } else {
            // verify that the device is in bootloader
            long timeout = YAPI.GetTickCount() + YPROG_BOOTLOADER_TIMEOUT;
            byte[] res;
            boolean found = false;
            progress.firmware_progress(40, "Wait for device to be in bootloader");
            do {
                ArrayList<String> list = getBootloaders();
                for (String bl : list) {
                    if (bl.equals(serial)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    Thread.sleep(100);
                }
            } while (!found && YAPI.GetTickCount() < timeout);
            //start flash
            progress.firmware_progress(45, "Flash firmware");
            res = req.RequestSync("GET /flash.json?a=flash&s=" + serial, null, yHTTPRequest.YIO_DEFAULT_TCP_TIMEOUT);
            try {
                String jsonstr = new String(res);
                JSONObject flashres = new JSONObject(jsonstr);
                JSONArray list = flashres.getJSONArray("logs");
                ArrayList<String> logs = new ArrayList<>(list.length());
                for (int i = 0; i < list.length(); i++) {
                    logs.add(list.getString(i));
                }
                return logs;
            } catch (JSONException ex) {
                throw new YAPI_Exception(YAPI.IO_ERROR, "invalid response");
            }
        }

        return null;
    }

    @Override
    synchronized void devRequestAsync(YDevice device, String req_first_line, byte[] req_head_and_body, RequestAsyncResult asyncResult, Object asyncContext) throws YAPI_Exception, InterruptedException
    {
        if (!_notificationHandler.isConnected()) {
            throw new YAPI_Exception(YAPI.TIMEOUT, "hub " + this._http_params.getUrl() + " is not reachable");
        }
        _notificationHandler.devRequestAsync(device, req_first_line, req_head_and_body, asyncResult, asyncContext);
    }

    @Override
    synchronized byte[] devRequestSync(YDevice device, String req_first_line, byte[] req_head_and_body) throws YAPI_Exception, InterruptedException
    {
        if (!_notificationHandler.isConnected()) {
            throw new YAPI_Exception(YAPI.TIMEOUT, "hub " + this._http_params.getUrl() + " is not reachable");
        }
        return _notificationHandler.devRequestSync(device, req_first_line, req_head_and_body, yHTTPRequest.YIO_DEFAULT_TCP_TIMEOUT);
    }

    String getHost()
    {
        return _http_params.getHost();
    }

    int getPort()
    {
        return _http_params.getPort();
    }

    @Override
    public ArrayList<String> getBootloaders() throws YAPI_Exception, InterruptedException
    {
        ArrayList<String> res = new ArrayList<>();
        byte[] raw_data = _notificationHandler.hubRequestSync("GET /flash.json?a=list", null, yHTTPRequest.YIO_DEFAULT_TCP_TIMEOUT);
        String jsonstr = new String(raw_data);
        try {
            JSONObject flashres = new JSONObject(jsonstr);
            JSONArray list = flashres.getJSONArray("list");
            for (int i = 0; i < list.length(); i++) {
                res.add(list.getString(i));
            }
        } catch (JSONException ex) {
            throw new YAPI_Exception(YAPI.IO_ERROR, "Unable to retrieve bootloader list");
        }
        return res;
    }

    @Override
    public int ping(int mstimeout) throws YAPI_Exception
    {
        // ping dot not use Notification handler but a one shot http request
        yHTTPRequest req = new yHTTPRequest(this, "getBootloaders");
        req.RequestSync("GET /api/module/firmwareRelease.json", null, mstimeout);
        return YAPI.SUCCESS;
    }

}