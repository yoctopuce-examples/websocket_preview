<%--
  Created by IntelliJ IDEA.
  User: seb
  Date: 24.12.2015
  Time: 08:52
  To change this template use File | Settings | File Templates.
--%>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<html>
<head>
    <title>Yoctopuce WebSocket Callback</title>
</head>
<body>
Setup the HTTP callback of  your YoctoHub or VirtualHub as "Yocto-API callback" and use the URL ws://<%=request.getServerName()%>:<%=request.getLocalPort()%><%=request.getRequestURI()%>wscallback
</body>
</html>
