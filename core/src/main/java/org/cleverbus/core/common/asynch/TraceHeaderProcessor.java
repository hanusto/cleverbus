/*
 * Copyright (C) 2015
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cleverbus.core.common.asynch;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.ValidationEvent;
import javax.xml.bind.ValidationEventHandler;
import javax.xml.transform.Source;

import org.cleverbus.api.asynch.model.TraceHeader;
import org.cleverbus.api.asynch.model.TraceIdentifier;
import org.cleverbus.api.common.ExchangeConstants;
import org.cleverbus.api.exception.InternalErrorEnum;
import org.cleverbus.api.exception.ValidationIntegrationException;
import org.cleverbus.common.log.Log;
import org.cleverbus.core.common.validator.TraceIdentifierValidator;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.springframework.util.Assert;
import org.springframework.ws.soap.SoapHeaderElement;


/**
 * Processor that gain trace header information and save it as message header {@link #TRACE_HEADER} in exchange.
 * <p/>
 * Processor works with our input asynchronous messages (trace header is in SOAP header)
 * and also with input messages (trace header is in SOAP body).
 * <p/>
 * Trace header is mandatory by default but you can change it.
 *
 * @author <a href="mailto:petr.juza@cleverlance.com">Petr Juza</a>
 */
public class TraceHeaderProcessor implements Processor {

    /**
     * Header value that holds {@link TraceHeader}.
     */
    public static final String TRACE_HEADER = ExchangeConstants.TRACE_HEADER;

    public static final String TRACE_HEADER_ELM = "traceHeader";

    private final JAXBContext jaxb2;
    private final ValidationEventHandler validationEventHandler = getValidationEventHandler();

    /**
     * {@code true} when trace header is mandatory, or {@code false} when nothing happens when there is no trace header.
     */
    private final boolean mandatoryHeader;

    /**
     * collection of trace identifier validators
     */
    private List<TraceIdentifierValidator> validatorList;

    /**
     * Creates immutable Trace Header processor.
     *
     * @param mandatoryHeader if trace header is mandatory
     * @param validatorList   the collection of trace identifier validators
     * @throws JAXBException
     */
    public TraceHeaderProcessor(boolean mandatoryHeader, @Nullable List<TraceIdentifierValidator> validatorList) throws
            JAXBException {
        jaxb2 = JAXBContext.newInstance(TraceHeader.class);
        this.mandatoryHeader = mandatoryHeader;
        this.validatorList = validatorList;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        // check existence of trace header in the exchange
        if (exchange.getIn().getHeader(TRACE_HEADER) != null) {
            return;
        }

        SoapHeaderElement traceHeaderElm = exchange.getIn().getHeader(TRACE_HEADER_ELM, SoapHeaderElement.class);
        if (traceHeaderElm != null) {
            setFromTraceHeader(exchange, traceHeaderElm.getSource(), false);
            return;
        }

        try {
            // try unmarshalling body as TraceHeader (it could be TraceHeader child)
            Source traceHeaderElmSource = exchange.getIn().getBody(Source.class);
            if (traceHeaderElmSource != null) {
                setFromTraceHeader(exchange, traceHeaderElmSource, true);
                return;
            }
        } catch (JAXBException exc) {
            Log.debug("Failed to unmarshal body as TraceHeader", exc);
        }

        if (isMandatoryHeader()) {
            throw new ValidationIntegrationException(InternalErrorEnum.E104);
        }
    }

    private void setFromTraceHeader(Exchange exchange, Source traceHeaderElmSource, boolean headerInBody) throws JAXBException {
        // unmarshal
        Unmarshaller unmarshaller = jaxb2.createUnmarshaller();
        if (!headerInBody) {
            // if there is trace header in the body then error events are thrown because there are other elements
            //  in the body
            unmarshaller.setEventHandler(validationEventHandler);
        }

        TraceHeader traceHeader = unmarshaller.unmarshal(traceHeaderElmSource, TraceHeader.class).getValue();
        if (traceHeader == null) {
            if (isMandatoryHeader()) {
                throw new ValidationIntegrationException(InternalErrorEnum.E105, "there is no trace header");
            }
        } else {
            // validate header content
            TraceIdentifier traceId = traceHeader.getTraceIdentifier();
            if (traceId == null) {
                if (isMandatoryHeader()) {
                    throw new ValidationIntegrationException(InternalErrorEnum.E105, "there is no trace identifier");
                }
            } else {
                if (traceId.getApplicationID() == null) {
                    throw new ValidationIntegrationException(InternalErrorEnum.E105, "there is no application ID");

                } else if (traceId.getCorrelationID() == null) {
                    throw new ValidationIntegrationException(InternalErrorEnum.E105, "there is no correlation ID");

                } else if (traceId.getTimestamp() == null) {
                    throw new ValidationIntegrationException(InternalErrorEnum.E105, "there is no timestamp ID");
                }

                validateTraceIdentifier(traceId);
                exchange.getIn().setHeader(TRACE_HEADER, traceHeader);
                Log.debug("traceHeader saved to exchange: " + ToStringBuilder.reflectionToString(traceId));
            }
        }
    }

    private ValidationEventHandler getValidationEventHandler() {
        return new ValidationEventHandler() {
            public boolean handleEvent(ValidationEvent event) {
                if (event.getSeverity() == ValidationEvent.WARNING) {
                    Log.warn("Ignored {}", event, event.getLinkedException());
                    return true; // handled - ignore as WARNING does not prevent unmarshalling
                } else {
                    return false; // not handled - ERROR and FATAL_ERROR prevent successful unmarshalling
                }
            }
        };
    }

    /**
     * Checks that {@link TraceIdentifier} contains values which are valid.
     *
     * @param traceId the {@link TraceIdentifier}
     * @throws ValidationIntegrationException
     */
    private void validateTraceIdentifier(TraceIdentifier traceId) {
        Assert.notNull(traceId, "the traceId must not be null");

        // if not defined some implementation, the validation is skipped
        if (validatorList == null || validatorList.isEmpty()) {
            Log.debug("no traceIdentifier validator found");
            return;
        }

        for (TraceIdentifierValidator validator : validatorList) {
            if (validator.isValid(traceId)) {
                Log.debug("the trace identifier '{0}' is allowed", ToStringBuilder.reflectionToString(traceId));
                return;
            }
        }

        // trace identifier values was not found in any list of possible values
        throw new ValidationIntegrationException(InternalErrorEnum.E120,
                "the trace identifier '" + ToStringBuilder.reflectionToString(traceId,
                        ToStringStyle.SHORT_PREFIX_STYLE) + "' is not allowed");
    }

    /**
     * Returns {@code true} when trace header is mandatory,
     * or {@code false} when nothing happens when there is no trace header.
     *
     * @return if trace header is mandatory
     */
    public boolean isMandatoryHeader() {
        return mandatoryHeader;
    }

    /**
     * Returns list of {@link TraceIdentifierValidator}.
     *
     * @return the list of {@link TraceIdentifierValidator}
     */
    public List<TraceIdentifierValidator> getValidatorList() {
        return Collections.unmodifiableList(validatorList);
    }
}
