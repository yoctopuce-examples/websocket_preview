Preview of the Yoctopuce Java library with WebSocket support
============================================================

## License information

Copyright (C) 2011 and beyond by Yoctopuce Sarl, Switzerland.

Yoctopuce Sarl (hereafter Licensor) grants to you a perpetual
non-exclusive license to use, modify, copy and integrate this
file into your software for the sole purpose of interfacing
with Yoctopuce products.

You may reproduce and distribute copies of this file in
source or object form, as long as the sole purpose of this
code is to interface with Yoctopuce products. You must retain
this notice in the distributed source file.

You should refer to Yoctopuce General Terms and Conditions
for additional information regarding your rights and
obligations.

THE SOFTWARE AND DOCUMENTATION ARE PROVIDED "AS IS" WITHOUT
WARRANTY OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING
WITHOUT LIMITATION, ANY WARRANTY OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE, TITLE AND NON-INFRINGEMENT. IN NO
EVENT SHALL LICENSOR BE LIABLE FOR ANY INCIDENTAL, SPECIAL,
INDIRECT OR CONSEQUENTIAL DAMAGES, LOST PROFITS OR LOST DATA,
COST OF PROCUREMENT OF SUBSTITUTE GOODS, TECHNOLOGY OR
SERVICES, ANY CLAIMS BY THIRD PARTIES (INCLUDING BUT NOT
LIMITED TO ANY DEFENSE THEREOF), ANY CLAIMS FOR INDEMNITY OR
CONTRIBUTION, OR OTHER SIMILAR COSTS, WHETHER ASSERTED ON THE
BASIS OF CONTRACT, TORT (INCLUDING NEGLIGENCE), BREACH OF
WARRANTY, OR OTHERWISE.

## Content

This is a preview of our upcoming Java Library with WebSocket. This repository contain the source code of two examples
that are used in our article : http://www.yoctopuce.com/EN/article/remote-device-control-using-websocket-callbacks

**Don't use this code in production yet**. This code is still in development and may contain some bugs and some
functionality are still not finished (the md5 signature is still not implemented).We will make an official release by the end of January 2016, when the code
will be more mature and properly tested.


## WebSocket as client

This is a simple command line application that connect to a YoctoHub/VirtualHub list all Yoctopuce devices and toggle relay output.

Compile the application with:
```bash
$ mvn compile
```

And run it with :
```bash
$ mvn exec:java -Dexec.mainClass="com.yoctopuce.demo.App"
```


## WebSocket as server

This is a simple server application that must be deployed on a Java EE 7–compliant application servers (Tomcat 7, Tomcat 8, etc...)
The application implement a ServerEndpoint that follow the JSR 356 specification and wait for a incoming connection of a YoctoHub/VirtualHub.
When an incomming connection occur, the applicaiton will create a new thread that will ist all Yoctopuce devices and toggle relay output.

Compile the application with:
```bash
$ mvn package
```

And install the *demo-1.0.war* in your application server.
