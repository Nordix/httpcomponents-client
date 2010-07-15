/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.impl.client.cache;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.annotation.Immutable;
import org.apache.http.impl.cookie.DateParseException;
import org.apache.http.impl.cookie.DateUtils;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HTTP;

/**
 * Structure used to store an {@link HttpResponse} in a cache
 *
 * @since 4.1
 */
@Immutable
public class CacheEntry implements Serializable {

    private static final long serialVersionUID = -6300496422359477413L;

    public static final long MAX_AGE = 2147483648L;

    private final Date requestDate;
    private final Date responseDate;
    private final ProtocolVersion version;
    private final int status;
    private final String reason;
    private final CachedHeaderGroup responseHeaders;
    private final HttpEntity body;
    private final Set<String> variantURIs;

    /**
     * Create a new {@link CacheEntry}
     *
     * @param requestDate
     *          Date/time when the request was made (Used for age
     *            calculations)
     * @param responseDate
     *          Date/time that the response came back (Used for age
     *            calculations)
     * @param version
     *          HTTP Response Version
     * @param responseHeaders
     *          Header[] from original HTTP Response
     * @param body
     *          HttpEntity representing the body of the response
     * @param status
     *          Numeric HTTP Status Code
     * @param reason
     *          String message from HTTP Status Line
     */
    public CacheEntry(Date requestDate, Date responseDate, ProtocolVersion version,
            Header[] responseHeaders, HttpEntity body, int status, String reason) {
        super();
        this.requestDate = requestDate;
        this.responseDate = responseDate;
        this.version = version;
        this.responseHeaders = new CachedHeaderGroup();
        this.responseHeaders.setHeaders(responseHeaders);
        this.status = status;
        this.reason = reason;
        this.body = body;
        this.variantURIs = new HashSet<String>();

    }

    /**
     * Constructor used to create a copy of an existing entry, while adding another variant URI to it.
     *
     * @param entry CacheEntry to be duplicated
     * @param variantURI URI to add
     */
    private CacheEntry(CacheEntry entry, String variantURI){
        this(entry.getRequestDate(),
                entry.getResponseDate(),
                entry.getProtocolVersion(),
                entry.getAllHeaders(),
                entry.body,
                entry.getStatusCode(),
                entry.getReasonPhrase());
        this.variantURIs.addAll(entry.getVariantURIs());
        this.variantURIs.add(variantURI);
    }

    public CacheEntry copyWithVariant(String variantURI){
        return new CacheEntry(this,variantURI);
    }

    public ProtocolVersion getProtocolVersion() {
        return version;
    }

    public String getReasonPhrase() {
        return reason;
    }

    public int getStatusCode() {
        return status;
    }

    public Date getRequestDate() {
        return requestDate;
    }

    public Date getResponseDate() {
        return responseDate;
    }

    public HttpEntity getBody() {
        return body;
    }

    public Header[] getAllHeaders() {
        return responseHeaders.getAllHeaders();
    }

    public Header getFirstHeader(String name) {
        return responseHeaders.getFirstHeader(name);
    }

    public Header[] getHeaders(String name) {
        return responseHeaders.getHeaders(name);
    }

    public long getCurrentAgeSecs() {
        return getCorrectedInitialAgeSecs() + getResidentTimeSecs();
    }

    public long getFreshnessLifetimeSecs() {
        long maxage = getMaxAge();
        if (maxage > -1)
            return maxage;

        Date dateValue = getDateValue();
        if (dateValue == null)
            return 0L;

        Date expiry = getExpirationDate();
        if (expiry == null)
            return 0;
        long diff = expiry.getTime() - dateValue.getTime();
        return (diff / 1000);
    }

    public boolean isResponseFresh() {
        return (getCurrentAgeSecs() < getFreshnessLifetimeSecs());
    }

    /**
     *
     * @return boolean indicating whether ETag or Last-Modified responseHeaders
     *         are present
     */
    public boolean isRevalidatable() {
        return getFirstHeader(HeaderConstants.ETAG) != null
                || getFirstHeader(HeaderConstants.LAST_MODIFIED) != null;

    }

    public boolean modifiedSince(HttpRequest request) {
        Header unmodHeader = request.getFirstHeader(HeaderConstants.IF_UNMODIFIED_SINCE);

        if (unmodHeader == null) {
            return false;
        }

        try {
            Date unmodifiedSinceDate = DateUtils.parseDate(unmodHeader.getValue());
            Date lastModifiedDate = DateUtils.parseDate(getFirstHeader(
                    HeaderConstants.LAST_MODIFIED).getValue());

            if (unmodifiedSinceDate.before(lastModifiedDate)) {
                return true;
            }
        } catch (DateParseException e) {
            return false;
        }

        return false;
    }

    /**
     *
     * @return boolean indicating whether any Vary responseHeaders are present
     */
    public boolean hasVariants() {
        return (getFirstHeader(HeaderConstants.VARY) != null);
    }

    public Set<String> getVariantURIs() {
        return Collections.unmodifiableSet(this.variantURIs);
    }

    public boolean mustRevalidate() {
        return hasCacheControlDirective("must-revalidate");
    }
    public boolean proxyRevalidate() {
        return hasCacheControlDirective("proxy-revalidate");
    }

    Date getDateValue() {
        Header dateHdr = getFirstHeader(HTTP.DATE_HEADER);
        if (dateHdr == null)
            return null;
        try {
            return DateUtils.parseDate(dateHdr.getValue());
        } catch (DateParseException dpe) {
            // ignore malformed date
        }
        return null;
    }

    long getContentLengthValue() {
        Header cl = getFirstHeader(HTTP.CONTENT_LEN);
        if (cl == null)
            return -1;

        try {
            return Long.parseLong(cl.getValue());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * This matters for deciding whether the cache entry is valid to serve as a
     * response. If these values do not match, we might have a partial response
     *
     * @return boolean indicating whether actual length matches Content-Length
     */
    boolean contentLengthHeaderMatchesActualLength() {
        return getContentLengthValue() == body.getContentLength();
    }

    long getApparentAgeSecs() {
        Date dateValue = getDateValue();
        if (dateValue == null)
            return MAX_AGE;
        long diff = responseDate.getTime() - dateValue.getTime();
        if (diff < 0L)
            return 0;
        return (diff / 1000);
    }

    long getAgeValue() {
        long ageValue = 0;
        for (Header hdr : getHeaders(HeaderConstants.AGE)) {
            long hdrAge;
            try {
                hdrAge = Long.parseLong(hdr.getValue());
                if (hdrAge < 0) {
                    hdrAge = MAX_AGE;
                }
            } catch (NumberFormatException nfe) {
                hdrAge = MAX_AGE;
            }
            ageValue = (hdrAge > ageValue) ? hdrAge : ageValue;
        }
        return ageValue;
    }

    long getCorrectedReceivedAgeSecs() {
        long apparentAge = getApparentAgeSecs();
        long ageValue = getAgeValue();
        return (apparentAge > ageValue) ? apparentAge : ageValue;
    }

    long getResponseDelaySecs() {
        long diff = responseDate.getTime() - requestDate.getTime();
        return (diff / 1000L);
    }

    long getCorrectedInitialAgeSecs() {
        return getCorrectedReceivedAgeSecs() + getResponseDelaySecs();
    }

    Date getCurrentDate() {
        return new Date();
    }

    long getResidentTimeSecs() {
        long diff = getCurrentDate().getTime() - responseDate.getTime();
        return (diff / 1000L);
    }

    long getMaxAge() {
        long maxage = -1;
        for (Header hdr : getHeaders(HeaderConstants.CACHE_CONTROL)) {
            for (HeaderElement elt : hdr.getElements()) {
                if (HeaderConstants.CACHE_CONTROL_MAX_AGE.equals(elt.getName())
                        || "s-maxage".equals(elt.getName())) {
                    try {
                        long currMaxAge = Long.parseLong(elt.getValue());
                        if (maxage == -1 || currMaxAge < maxage) {
                            maxage = currMaxAge;
                        }
                    } catch (NumberFormatException nfe) {
                        // be conservative if can't parse
                        maxage = 0;
                    }
                }
            }
        }
        return maxage;
    }

    Date getExpirationDate() {
        Header expiresHeader = getFirstHeader(HeaderConstants.EXPIRES);
        if (expiresHeader == null)
            return null;
        try {
            return DateUtils.parseDate(expiresHeader.getValue());
        } catch (DateParseException dpe) {
            // malformed expires header
        }
        return null;
    }

    boolean hasCacheControlDirective(String directive) {
        for(Header h : responseHeaders.getHeaders("Cache-Control")) {
            for(HeaderElement elt : h.getElements()) {
                if (directive.equalsIgnoreCase(elt.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {

        // write CacheEntry
        out.defaultWriteObject();

        // write (non-serializable) responseHeaders
        if (null == responseHeaders || responseHeaders.getAllHeaders().length < 1)
            return;
        int headerCount = responseHeaders.getAllHeaders().length;
        Header[] headers = responseHeaders.getAllHeaders();
        String[][] sheaders = new String[headerCount][2];
        for (int i = 0; i < headerCount; i++) {
            sheaders[i][0] = headers[i].getName();
            sheaders[i][1] = headers[i].getValue();
        }
        out.writeObject(sheaders);

    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {

        // read CacheEntry
        in.defaultReadObject();

        // read (non-serializable) responseHeaders
        String[][] sheaders = (String[][]) in.readObject();
        if (null == sheaders || sheaders.length < 1)
            return;
        BasicHeader[] headers = new BasicHeader[sheaders.length];
        for (int i = 0; i < sheaders.length; i++) {
            String[] sheader = sheaders[i];
            headers[i] = new BasicHeader(sheader[0], sheader[1]);
        }

        this.responseHeaders.setHeaders(headers);
    }

    @Override
    public String toString() {
        return "[request date=" + requestDate + "; response date=" + responseDate
                + "; status=" + status + "]";
    }

}
