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

package org.cleverbus.api.asynch.msg;

import java.util.Date;
import java.util.UUID;

import javax.annotation.Nullable;

import org.cleverbus.api.entity.BindingTypeEnum;
import org.cleverbus.api.entity.EntityTypeExtEnum;
import org.cleverbus.api.entity.Message;
import org.cleverbus.api.entity.MsgStateEnum;
import org.cleverbus.api.entity.ServiceExtEnum;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.springframework.util.Assert;


/**
 * Represents child message.
 *
 * @author <a href="mailto:petr.juza@cleverlance.com">Petr Juza</a>
 * @see Message
 */
public final class ChildMessage {

    private Message parentMessage;

    private BindingTypeEnum bindingType = BindingTypeEnum.HARD;

    private ServiceExtEnum service;

    private String operationName;

    private String objectId;

    private EntityTypeExtEnum entityType;

    private String body;

    private String funnelValue;

    /**
     * Creates child message with specified binding type to parent message.
     *
     *
     * @param parentMessage the parent message
     * @param bindingType the binding type to parent message
     * @param service the service name, e.g. customer
     * @param operationName the operation name, e.g. createCustomer
     * @param body the new message (XML) body
     * @param objectId the object ID that will be changed during message processing.
     *                   This parameter serves for finding messages in the queue which deals with identical object.
     * @param entityType the type of the entity that is being changed.
     *                   This parameter serves for finding messages in the queue which deals with identical object,
     *                   see {@link Message#getEntityTypeInternal()} for more details
     * @param funnelValue the funnel value
     *
     */
    public ChildMessage(Message parentMessage, BindingTypeEnum bindingType, ServiceExtEnum service, String operationName,
            String body, @Nullable String objectId, @Nullable EntityTypeExtEnum entityType, @Nullable String funnelValue) {

        Assert.notNull(parentMessage, "the parentMessage must not be null");
        Assert.notNull(bindingType, "the bindingType must not be null");
        Assert.notNull(service, "the service must not be null");
        Assert.hasText(operationName, "the operationName must not be null");
        Assert.hasText(body, "the body must not be empty");

        this.parentMessage = parentMessage;
        this.bindingType = bindingType;
        this.service = service;
        this.operationName = operationName;
        this.objectId = objectId;
        this.entityType = entityType;
        this.body = body;
        this.funnelValue = funnelValue;
    }

    /**
     * Creates child message with {@link BindingTypeEnum#HARD HARD} binding type to parent message.
     *
     * @param parentMessage the parent message
     * @param service the service name, e.g. customer.
     * @param operationName the operation name, e.g. createCustomer.
     * @param body the new message (XML) body
     */
    public ChildMessage(Message parentMessage, ServiceExtEnum service, String operationName, String body) {
        this(parentMessage, BindingTypeEnum.HARD, service, operationName, body, null, null, null);
    }

    /**
     * Converts {@link ChildMessage} into a full {@link Message} that can be persisted or processed.
     *
     * @param childMsg the child message info
     * @return a new Message that is child of {@link ChildMessage#getParentMessage()}
     */
    public static Message createMessage(ChildMessage childMsg) {
        Assert.notNull(childMsg, "childMsg must not be null");

        Message parentMsg = childMsg.getParentMessage();

        if (childMsg.getBindingType() == BindingTypeEnum.HARD) {
            parentMsg.setParentMessage(true);
        }

        Date currDate = new Date();

        Message msg = new Message();

        // new fields
        msg.setState(MsgStateEnum.PROCESSING);
        msg.setStartProcessTimestamp(currDate);
        msg.setCorrelationId(UUID.randomUUID().toString());
        msg.setLastUpdateTimestamp(currDate);
        msg.setSourceSystem(parentMsg.getSourceSystem());

        // fields from parent
        msg.setParentMsgId(parentMsg.getMsgId());
        msg.setParentBindingType(childMsg.getBindingType());
        msg.setMsgTimestamp(parentMsg.getMsgTimestamp());
        msg.setReceiveTimestamp(parentMsg.getReceiveTimestamp());
        msg.setProcessId(parentMsg.getProcessId());

        // fields from child
        msg.setService(childMsg.getService());
        msg.setOperationName(childMsg.getOperationName());
        msg.setObjectId(childMsg.getObjectId());
        msg.setEntityType(childMsg.getEntityType());
        msg.setPayload(childMsg.getBody());
        msg.setFunnelValue(childMsg.getFunnelValue());

        return msg;
    }

    public Message getParentMessage() {
        return parentMessage;
    }

    public BindingTypeEnum getBindingType() {
        return bindingType;
    }

    public ServiceExtEnum getService() {
        return service;
    }

    public String getOperationName() {
        return operationName;
    }

    @Nullable
    public String getObjectId() {
        return objectId;
    }

    @Nullable
    public EntityTypeExtEnum getEntityType() {
        return entityType;
    }

    public String getBody() {
        return body;
    }

    @Nullable
    public String getFunnelValue() {
        return funnelValue;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
            .append("parentMessage", parentMessage)
            .append("bindingType", bindingType)
            .append("service", service != null ? service.getServiceName() : null)
            .append("operationName", operationName)
            .append("objectId", objectId)
            .append("entityType", entityType != null ? entityType.getEntityType() : null)
            .append("funnelValue", funnelValue)
            .append("body", StringUtils.substring(body, 0, 500))
            .toString();
    }
}
