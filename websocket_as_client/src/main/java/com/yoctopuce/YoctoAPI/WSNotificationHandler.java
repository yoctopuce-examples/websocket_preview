package com.yoctopuce.YoctoAPI;

import javax.websocket.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.*;

@ClientEndpoint
public class WSNotificationHandler extends NotificationHandler implements MessageHandler
{

    public static final int NB_TCP_CHANNEL = 8;
    public static final int HUB_TCP_CHANNEL = 1;
    public static final int DEVICE_TCP_CHANNEL = 0;
    public static final int DEVICE_ASYNC_TCP_CHANNEL = 2;
    public static final int WS_REQUEST_MAX_DURATION = 50000;
    private final ExecutorService _executorService;
    private final boolean _isHttpCallback;
    private Session _session;
    private final BlockingQueue<WSRequest> _outRequest = new LinkedBlockingQueue<>();
    private final ArrayList<BlockingQueue<WSRequest>> _inRequest = new ArrayList<>(NB_TCP_CHANNEL);
    private final WSRequest[] _workingRequests = new WSRequest[NB_TCP_CHANNEL];


    private final StreamState[] _streamsState = new StreamState[NB_TCP_CHANNEL];
    private final Object _stateLock = new Object();
    //private YAPI_Exception _error = null;
    private ConnectionState _connectionState = ConnectionState.CONNECTING;
    private volatile boolean _firstNotif;

    private enum ConnectionState
    {
        DEAD, DISCONNECTED, CONNECTING, CONNECTED
    }

    private enum StreamState
    {
        AVAIL, USED
    }


    public WSNotificationHandler(YHTTPHub hub, Session session)
    {
        super(hub);
        _session = session;
        _isHttpCallback = session != null;
        for (int i = 0; i < NB_TCP_CHANNEL; i++) {
            _inRequest.add(i, new LinkedBlockingQueue<WSRequest>(8));
            _workingRequests[i] = null;
            _streamsState[i] = StreamState.AVAIL;
        }
        _executorService = Executors.newFixedThreadPool(1);

    }


    @Override
    public void run()
    {
        _firstNotif = true;
        if (_isHttpCallback) {
            // server mode
            Whole<ByteBuffer> messageHandler = new Whole<ByteBuffer>()
            {

                @Override
                public void onMessage(ByteBuffer byteBuffer)
                {
                    parseBinaryMessage(byteBuffer);
                }
            };
            _session.addMessageHandler(messageHandler);
            runOnSession();
        } else {
            // client mode
            WebSocketContainer webSocketContainer = ContainerProvider.getWebSocketContainer();
            String url = "ws://" + _hub._http_params.getUrl() + "/not.byn";
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException e) {
                e.printStackTrace();
                return;
            }
            while (!Thread.currentThread().isInterrupted()) {
                if (_error_delay > 0) {
                    try {
                        Thread.sleep(_error_delay);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                synchronized (_stateLock) {
                    _connectionState = ConnectionState.CONNECTING;
                }
                try {
                    _session = webSocketContainer.connectToServer(this, uri);
                    runOnSession();
                } catch (DeploymentException | IOException e) {
                    e.printStackTrace();
                }
                _firstNotif = true;
                _notifRetryCount++;
                _hub._devListValidity = 500;
                _error_delay = 100 << (_notifRetryCount > 4 ? 4 : _notifRetryCount);
            }
        }
        try {
            _session.close();
        } catch (IOException ignored) {
            ignored.printStackTrace();
        }
        _session = null;
        synchronized (_stateLock) {
            _connectionState = ConnectionState.DEAD;
        }
    }

    private void runOnSession()
    {
        if (!_session.isOpen()) {
            _hub._yctx._Log("Websocket is not open");
            return;
        }
        RemoteEndpoint.Basic basicRemote = _session.getBasicRemote();
        try {
            long timeout = System.currentTimeMillis() + 10000;
            synchronized (_stateLock) {
                while (_connectionState == ConnectionState.CONNECTING) {
                    _stateLock.wait(1000);
                    if (timeout < System.currentTimeMillis()) {
                        _hub._yctx._Log("YoctoHub did not send any data for 10 secs\n");
                        _connectionState = ConnectionState.DISCONNECTED;
                        _stateLock.notifyAll();
                        return;
                    }
                }
            }

            while (!Thread.currentThread().isInterrupted()) {
                WSRequest request = null;
                request = _outRequest.take();
                if (request.getErrorCode() != YAPI.SUCCESS) {
                    // fake request to unlock thread
                    break;
                }
                int tcpchanel = request.getChannel();
                if (request._state == WSRequest.State.OPEN) {
                    synchronized (_workingRequests) {
                        _workingRequests[tcpchanel] = request;
                    }
                    byte[] requestBytes = request.getRequestBytes();
                    ByteBuffer requestBB = ByteBuffer.wrap(requestBytes);
                    while (requestBB.hasRemaining()) {
                        int size = requestBB.remaining();
                        int type = YGenericHub.YSTREAM_TCP;
                        if (size > WSStream.MAX_DATA_LEN) {
                            size = WSStream.MAX_DATA_LEN;
                        }
                        //todo look if it's possible to prevent byte[] allocation
                        WSStream stream = new WSStream(type, tcpchanel, size, requestBB);
                        byte[] data = stream.getData();
                        ByteBuffer wrap = ByteBuffer.wrap(data);
                        basicRemote.sendBinary(wrap, true);
                    }
                } else {
                    if (request.getState() == WSRequest.State.CLOSED_BY_API) {
                        // early close by API
                        synchronized (_workingRequests) {
                            _workingRequests[tcpchanel] = request;
                        }
                    }
                    WSStream stream = new WSStream(YGenericHub.YSTREAM_TCP_CLOSE, tcpchanel, 0, null);
                    byte[] data = stream.getData();
                    ByteBuffer wrap = ByteBuffer.wrap(data);
                    basicRemote.sendBinary(wrap, true);
                }
            }
        } catch (IOException | InterruptedException ex) {
            // put all request in error
            synchronized (_workingRequests) {
                for (int i = 0; i < NB_TCP_CHANNEL; i++) {
                    WSRequest request = _workingRequests[i];
                    if (request != null) {
                        request.setError(YAPI.IO_ERROR, ex.getLocalizedMessage(), ex);
                        int channel = request.getChannel();
                        BlockingQueue<WSRequest> wsRequests = _inRequest.get(channel);
                        wsRequests.offer(request);
                        _workingRequests[i] = null;
                    }
                }
            }
        }
        synchronized (_stateLock) {
            _connectionState = ConnectionState.DISCONNECTED;
            _stateLock.notifyAll();
        }

    }

    private void sendRequest(String req_first_line, byte[] req_head_and_body, int tcpchanel) throws YAPI_Exception, InterruptedException
    {
        byte[] full_request;
        byte[] req_first_lineBytes;
        if (req_head_and_body == null) {
            req_first_line += "\r\n\r\n";
            req_first_lineBytes = req_first_line.getBytes();
            full_request = req_first_lineBytes;
        } else {
            req_first_line += "\r\n";
            req_first_lineBytes = req_first_line.getBytes();
            full_request = new byte[req_first_lineBytes.length + req_head_and_body.length];
            System.arraycopy(req_first_lineBytes, 0, full_request, 0, req_first_lineBytes.length);
            System.arraycopy(req_head_and_body, 0, full_request, req_first_lineBytes.length, req_head_and_body.length);
        }

        long timeout = System.currentTimeMillis() + WS_REQUEST_MAX_DURATION;
        synchronized (_stateLock) {
            while (_connectionState != ConnectionState.CONNECTED || _streamsState[tcpchanel] != StreamState.AVAIL) {
                _stateLock.wait(1000);
                if (timeout < System.currentTimeMillis()) {
                    if (_connectionState != ConnectionState.CONNECTED && _connectionState != ConnectionState.CONNECTING) {
                        throw new YAPI_Exception(YAPI.IO_ERROR, "IO error with hub");
                    } else {
                        throw new YAPI_Exception(YAPI.TIMEOUT, "Last request did not finished correctly");
                    }
                }
            }
            _streamsState[tcpchanel] = StreamState.USED;
        }
        WSRequest request = new WSRequest(tcpchanel, full_request);
        _outRequest.put(request);
    }

    private byte[] getRequestResponse(int tcpchanel, int mstimeout) throws YAPI_Exception, InterruptedException
    {
        WSRequest request;
        long timeout = System.currentTimeMillis() + mstimeout;
        do {
            request = _inRequest.get(tcpchanel).poll(500, TimeUnit.MILLISECONDS);
        } while (request == null && timeout > System.currentTimeMillis());


        if (request == null) {
            throw new YAPI_Exception(YAPI.TIMEOUT, "Timeout during device request");
        }

        if (request.getState() != WSRequest.State.CLOSED_BY_HUB) {
            request.checkError();
            throw new YAPI_Exception(YAPI.IO_ERROR, "Unknown error");
        }
        byte[] full_result = request.getResponseBytes();
        // resend request to send final close
        _outRequest.put(request);
        synchronized (_stateLock) {
            _streamsState[tcpchanel] = StreamState.AVAIL;
            _stateLock.notifyAll();
        }

        int okpos = YAPIContext._find_in_bytes(full_result, "OK".getBytes());
        int hpos = YAPIContext._find_in_bytes(full_result, "\r\n\r\n".getBytes());
        if (okpos == 0 && hpos >= 0) {
            return Arrays.copyOfRange(full_result, hpos + 4, full_result.length);
        }
        return full_result;
    }

    @Override
    public byte[] hubRequestSync(String req_first_line, byte[] req_head_and_body, int mstimeout) throws
            YAPI_Exception, InterruptedException
    {
        int tcpchanel = HUB_TCP_CHANNEL;
        sendRequest(req_first_line, req_head_and_body, tcpchanel);
        return getRequestResponse(tcpchanel, mstimeout);
    }


    @Override
    byte[] devRequestSync(YDevice device, String req_first_line, byte[] req_head_and_body, int mstimeout) throws
            YAPI_Exception, InterruptedException
    {
        int tcpchanel = DEVICE_TCP_CHANNEL;
        sendRequest(req_first_line, req_head_and_body, tcpchanel);
        return getRequestResponse(tcpchanel, mstimeout);
    }

    @Override
    void devRequestAsync(YDevice device, String req_first_line, byte[] req_head_and_body,
                         final YGenericHub.RequestAsyncResult asyncResult, final Object asyncContext) throws
            YAPI_Exception, InterruptedException
    {
        final int tcpchanel = DEVICE_TCP_CHANNEL;
        sendRequest(req_first_line, req_head_and_body, tcpchanel);
        _executorService.execute(new Runnable()
        {
            @Override
            public void run()
            {
                byte[] response = null;
                int error_code = YAPI.SUCCESS;
                String errmsg = null;
                try {
                    response = getRequestResponse(tcpchanel, yHTTPRequest.YIO_DEFAULT_TCP_TIMEOUT);
                } catch (YAPI_Exception e) {
                    error_code = e.errorType;
                    errmsg = e.getLocalizedMessage();
                } catch (InterruptedException e) {
                    error_code = YAPI.IO_ERROR;
                    errmsg = e.getLocalizedMessage();
                }
                if (asyncResult != null) {
                    asyncResult.RequestAsyncDone(asyncContext, response, error_code, errmsg);
                }
            }
        });
    }

    @Override
    boolean waitAndFreeAsyncTasks(long timeout) throws InterruptedException
    {
        _executorService.shutdown();
        boolean stillPending = !_executorService.awaitTermination(timeout, TimeUnit.MILLISECONDS);
        try {
            _session.close();
        } catch (IOException e) {
            _hub._yctx._Log("error on ws close : " + e.getMessage());
            e.printStackTrace();
        }
        return stillPending;
    }

    @Override
    public boolean isConnected()
    {
        synchronized (_stateLock) {
            return _connectionState == ConnectionState.CONNECTED || _connectionState == ConnectionState.CONNECTING;
        }
    }

    static class WSRequest
    {
        private final int _channel;
        private final byte[] _requestData;
        private final LinkedList<WSStream> _responseStream;
        private volatile State _state;
        private YAPI_Exception _error = null;
        private int _errorCode = YAPI.SUCCESS;
        private String _errorMsg = null;
        private Exception _errorEx = null;

        public void checkError() throws YAPI_Exception
        {
            if (_errorCode != YAPI.SUCCESS) {
                throw new YAPI_Exception(_errorCode, _errorMsg, _errorEx);
            }
        }

        public void setError(int ioError, String reasonPhrase, Exception ex)
        {
            _errorCode = ioError;
            _errorMsg = reasonPhrase;
            _errorEx = ex;
            _state = State.ERROR;
        }

        public int getErrorCode()
        {
            return _errorCode;
        }

        enum State
        {
            OPEN, CLOSED_BY_HUB, CLOSED_BY_API, CLOSED, ERROR
        }

        public WSRequest(int tcpchanel, byte[] full_request)
        {
            _state = State.OPEN;
            _channel = tcpchanel;
            _requestData = full_request;
            _responseStream = new LinkedList<>();
        }

        public byte[] getRequestBytes()
        {
            return _requestData;
        }

        public int getChannel()
        {
            return _channel;
        }

        public void setState(State state)
        {
            _state = state;
        }

        public State getState()
        {
            return _state;
        }

        public void addStream(WSStream stream)
        {
            _responseStream.add(stream);
        }

        public byte[] getResponseBytes()
        {
            int size = 0;
            for (WSStream s : _responseStream) {
                size += s.getContenLen();
            }

            byte[] full_result = new byte[size];
            ByteBuffer bb = ByteBuffer.wrap(full_result);
            for (WSStream s : _responseStream) {
                s.getContenLen(bb);
            }
            return full_result;
        }

    }

    static class WSStream
    {
        public static int MAX_DATA_LEN = 124;
        private final int _channel;
        private final int _type;
        private final int _contentLen;
        private final byte[] _data;


        public WSStream(int type, int channel, int size, ByteBuffer requestBB)
        {
            _channel = channel;
            _type = type;
            _data = new byte[size + 1];
            _data[0] = (byte) (((type << 3) + channel) & 0xff);
            _contentLen = size;
            if (size > 0) {
                requestBB.get(_data, 1, size);
            }
        }

        public byte[] getData()
        {
            return _data;
        }

        public int getChannel()
        {
            return _channel;
        }

        public int getType()
        {
            return _type;
        }


        @Override
        public String toString()
        {
            String content = new String(_data, 1, _contentLen, StandardCharsets.ISO_8859_1);
            return String.format("[c=%d t=%d l=%d]:[%s]", _channel, _type, _contentLen, content);
        }

        public int getContenLen()
        {
            return _contentLen;
        }

        public void getContenLen(ByteBuffer bb)
        {
            bb.put(_data, 1, _contentLen);
        }

    }


    @OnOpen
    public void onOpen(Session session)
    {
        _session = session;
    }


    @OnMessage
    public void onMessage(ByteBuffer raw_data, Session session)
    {
        if (_session != session) {
            return;
        }
        parseBinaryMessage(raw_data);
    }

    private void parseBinaryMessage(ByteBuffer raw_data)
    {
        byte first_byte = raw_data.get();
        int tcpChanel = first_byte & 0x7;
        int ystream = (first_byte & 0xff) >> 3;
        switch (ystream) {
            case YGenericHub.YSTREAM_TCP_NOTIF:
                byte[] chars = new byte[raw_data.remaining()];
                raw_data.get(chars);
                String tcpNotif = new String(chars, StandardCharsets.ISO_8859_1);
                decodeTCPNotif(tcpNotif);
                if (_firstNotif) {
                    synchronized (_stateLock) {
                        _connectionState = ConnectionState.CONNECTED;
                        _stateLock.notifyAll();
                    }
                    _firstNotif = false;
                }
                break;
            case YGenericHub.YSTREAM_EMPTY:
                return;
            case YGenericHub.YSTREAM_TCP:
            case YGenericHub.YSTREAM_TCP_CLOSE:
                WSStream stream = new WSStream(ystream, tcpChanel, raw_data.remaining(), raw_data);
                WSRequest workingRequest;
                synchronized (_workingRequests) {
                    workingRequest = _workingRequests[tcpChanel];
                }
                if (workingRequest != null) {
                    workingRequest.addStream(stream);
                    if (stream.getType() == YGenericHub.YSTREAM_TCP_CLOSE) {
                        synchronized (_workingRequests) {
                            _workingRequests[tcpChanel] = null;
                        }
                        if (workingRequest.getState() == WSRequest.State.OPEN) {
                            workingRequest.setState(WSRequest.State.CLOSED_BY_HUB);
                            try {
                                _inRequest.get(tcpChanel).put(workingRequest);
                            } catch (InterruptedException ignore) {
                                //ignore.printStackTrace();
                                // we can swallow this interruption because we are always in
                                // our own thread
                                return;
                            }
                        } else {
                            workingRequest.setState(WSRequest.State.CLOSED);
                        }
                    }

                }
                break;
            case YGenericHub.YSTREAM_NOTICE:
            case YGenericHub.YSTREAM_REPORT:
            case YGenericHub.YSTREAM_META:
            case YGenericHub.YSTREAM_REPORT_V2:
            case YGenericHub.YSTREAM_NOTICE_V2:
            default:
                _hub._yctx._Log(String.format("Invalid WS stream type (%d)", ystream));
        }
    }


    private final StringBuilder _notificationsFifo = new StringBuilder();

    private void decodeTCPNotif(String tcpNofif)
    {
        _notificationsFifo.append(tcpNofif);
        int pos;
        do {
            pos = _notificationsFifo.indexOf("\n");
            if (pos < 0) break;
            // discard ping notification (pos==0)
            if (pos > 0) {
                String line = _notificationsFifo.substring(0, pos + 1);
                if (line.indexOf(27) == -1) {
                    // drop notification that contain esc char
                    handleNetNotification(line);
                }
            }
            _notificationsFifo.delete(0, pos + 1);
        }

        while (pos >= 0);
    }


    @OnClose
    public void onClose(Session session, CloseReason closeReason)
    {
        try {
            _session.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        WSRequest fakeRequest = new WSRequest(0, null);
        fakeRequest.setState(WSRequest.State.ERROR);
        fakeRequest.setError(YAPI.IO_ERROR, closeReason.getReasonPhrase(), null);
        try {
            _outRequest.put(fakeRequest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            //e.printStackTrace();
        }
    }


    @OnError
    public void onError(Session session, Throwable throwable)
    {
        throwable.printStackTrace();
    }
}
