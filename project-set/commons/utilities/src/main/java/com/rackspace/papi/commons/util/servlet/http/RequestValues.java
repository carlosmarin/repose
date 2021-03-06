package com.rackspace.papi.commons.util.servlet.http;

/**
 *
 * @author Dan Daley
 */
public interface RequestValues {

    RequestDestinations getDestinations();

    RequestHeaderValues getHeaders();

    RequestQueryParameters getQueryParameters();

    String getRequestURI();

    StringBuffer getRequestURL();

    void setRequestURI(String uri);

    void setRequestURL(StringBuffer url);
    
}
