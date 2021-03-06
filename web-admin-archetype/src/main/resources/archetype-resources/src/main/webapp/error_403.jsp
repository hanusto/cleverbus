#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
<%@ page contentType="text/html;charset=UTF-8" language="java" isErrorPage="true" %>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html>
<head>
    <title>Doh! - CleverBus - integration framework</title>
    <meta http-equiv="X-UA-Compatible" content="IE=9">
    <meta http-equiv="content-type" content="text/html; charset=utf-8">
    <link rel="stylesheet" type="text/css" media="screen" href="${symbol_dollar}{pageContext.request.contextPath}/css/main.css">
    <link rel="stylesheet" type="text/css" href="${symbol_dollar}{pageContext.request.contextPath}/css/error_tmpl.css"/>
</head>
<body>

<div class="header">
    <div class="headerWrapper">
        <span class="logo"><a href="${symbol_dollar}{pageContext.request.contextPath}">
            <img class="logo" src="${symbol_dollar}{pageContext.request.contextPath}/css/images/logo-cbs.png" alt="CleverBus"/></a></span>
        <span class="headline">Integration framework</span>
    </div>
</div>

<div class="page">

    <div id="error-out"><h1>Forbidden</h1>

        <div id="error-out-desc">
            <h3>HTTP 403 Permission Required</h3>

            <p id="error-code"><b>Error code:</b> ${symbol_dollar}{pageContext.errorData.statusCode}</p>

            <p id="error-request-uri"><b>Request
                URI:</b> ${symbol_dollar}{pageContext.request.scheme}://${symbol_dollar}{header.host}${symbol_dollar}{pageContext.errorData.requestURI}</p>
            <p>You do not have permission to view this resource using the credentials you resource,
                please log in correctly or just return
                <a href="javascript:history.back()">back to the previous page</a>
            </p>

        </div>
    </div>

</div>
<div class="footer">
    <div class="footerWrapper">
        <span class="companyLogo"><a href="http://www.cleverbus.org" target="_blank">CleverBus</a></span>
    </div>
</div>
</body>
</html>