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

package org.cleverbus.core.common.asynch.msg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.cleverbus.api.entity.ExternalSystemExtEnum;
import org.cleverbus.api.entity.Message;
import org.cleverbus.api.entity.MsgStateEnum;
import org.cleverbus.api.exception.ErrorExtEnum;
import org.cleverbus.common.log.Log;
import org.cleverbus.core.common.dao.MessageDao;
import org.cleverbus.core.common.exception.ExceptionTranslator;
import org.cleverbus.spi.msg.MessageService;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;


/**
 * JPA implementation of the {@link MessageService} interface.
 *
 * @author <a href="mailto:petr.juza@cleverlance.com">Petr Juza</a>
 */
public class MessageServiceImpl implements MessageService {

    @Autowired
    private MessageDao messageDao;

    @Transactional
    @Override
    public void insertMessages(Collection<Message> messages) {
        Assert.notNull(messages, "the messages must not be null");

        for (Message msg : messages) {
            Assert.state(msg.getState() == MsgStateEnum.NEW || msg.getState() == MsgStateEnum.PROCESSING,
                    "new message can be in NEW or PROCESSING state only");

            msg.setLastUpdateTimestamp(new Date());

            messageDao.insert(msg);

            Log.debug("Inserted new message " + msg.toHumanString());
        }
    }

    @Transactional
    @Override
    public void setStateOk(Message msg, Map<String, Object> props) {
        Assert.notNull(msg, "the msg must not be null");
        Assert.isTrue(!msg.isParentMessage(), "the message must not be parent");

        msg.setState(MsgStateEnum.OK);
        msg.setLastUpdateTimestamp(new Date());

        // move new business errors to message:
        MessageHelper.updateBusinessErrors(msg, props);

        messageDao.update(msg);

        Log.debug("State of the message " + msg.toHumanString() + " was changed to " + MsgStateEnum.OK);

        // check parent message with HARD binding - if any
        if (msg.existHardParent()) {
            List<Message> childMessages = messageDao.findChildMessages(msg);

            // are all child messages processed?
            boolean finishedOK = true;
            for (Message childMsg : childMessages) {
                //note: input message doesn't have to be in valid state in DB if Hibernate is used ...
                if (childMsg.getState() != MsgStateEnum.OK && !childMsg.equals(msg)) {
                    finishedOK = false;
                    break;
                }
            }

            if (finishedOK) {
                // mark parent message as successfully processed
                Message parentMsg = messageDao.getMessage(msg.getParentMsgId());

                parentMsg.setState(MsgStateEnum.OK);
                parentMsg.setLastUpdateTimestamp(new Date());

                // extract business errors from all child messages
                parentMsg.setBusinessError(getBusinessErrorsFromChildMessages(childMessages));

                messageDao.update(parentMsg);

                Log.debug("State of the parent message " + parentMsg.toHumanString() + " was changed to " + MsgStateEnum.OK);
            }
        }
    }

    @Transactional
    @Override
    public void setStateProcessing(Message msg) {
        Assert.notNull(msg, "the msg must not be null");
        Assert.isTrue(msg.getState() == MsgStateEnum.WAITING_FOR_RES,
                "the message must be in WAITING_FOR_RES state, but state is " + msg.getState());

        msg.setState(MsgStateEnum.PROCESSING);
        Date currDate = new Date();
        msg.setStartProcessTimestamp(currDate);
        msg.setLastUpdateTimestamp(currDate);

        messageDao.update(msg);

        Log.debug("State of the message " + msg.toHumanString() + " was changed to " + MsgStateEnum.PROCESSING);
    }

    private String getBusinessErrorsFromChildMessages(List<Message> messages) {
        List<String> errorList = new ArrayList<String>();

        for (Message msg : messages) {
            errorList.addAll(msg.getBusinessErrorList());
        }

        return StringUtils.join(errorList, Message.ERR_DESC_SEPARATOR);
    }

    @Transactional
    @Override
    public void setStateWaiting(Message msg) {
        Assert.notNull(msg, "the msg must not be null");
        Assert.isTrue(msg.isParentMessage(), "the message must be parent");

        // check current state (it's possible that parent message has been already finished)
        Message currMsg = messageDao.getMessage(msg.getMsgId());

        if (currMsg.getState() == MsgStateEnum.PROCESSING) {
            msg.setState(MsgStateEnum.WAITING);
            msg.setLastUpdateTimestamp(new Date());

            messageDao.update(msg);

            Log.debug("State of the message " + msg.toHumanString() + " was changed to " + MsgStateEnum.WAITING);
        }
    }

    @Transactional
    @Override
    public void setStateWaitingForResponse(Message msg) {
        Assert.notNull(msg, "the msg must not be null");

        // note: we can send more VF messages in one asynch. message but one by one
        Assert.isTrue(msg.getState() == MsgStateEnum.PROCESSING,
                "the message must be in PROCESSING state, but state is " + msg.getState());

        if (msg.getState() != MsgStateEnum.WAITING_FOR_RES) {
            msg.setState(MsgStateEnum.WAITING_FOR_RES);
            msg.setLastUpdateTimestamp(new Date());

            messageDao.update(msg);

            Log.debug("State of the message " + msg.toHumanString() + " was changed to " + MsgStateEnum.WAITING_FOR_RES);
        } else {
            Log.debug("Message " + msg.toHumanString() + " was already in " + MsgStateEnum.WAITING_FOR_RES + " state.");
        }
    }

    @Transactional
    @Override
    public void setStatePartlyFailedWithoutError(Message msg) {
        Assert.notNull(msg, "the msg must not be null");
        Assert.isTrue(!msg.isParentMessage(), "the message must not be parent");

        msg.setState(MsgStateEnum.PARTLY_FAILED);
        msg.setLastUpdateTimestamp(new Date());

        messageDao.update(msg);

        Log.debug("State of the message " + msg.toHumanString() + " was changed to " + MsgStateEnum.PARTLY_FAILED
                + ", but WITHOUT increasing error counter");
    }

    @Transactional
    @Override
    public void setStatePartlyFailed(Message msg, Exception ex, ErrorExtEnum errCode, String customData,
            Map<String, Object> props) {

        Assert.notNull(msg, "the msg must not be null");

        msg.setState(MsgStateEnum.PARTLY_FAILED);
        updateErrorMessage(msg, ex, errCode, customData, props);

        Log.debug("State of the message " + msg.toHumanString() + " was changed to "
                + MsgStateEnum.PARTLY_FAILED + " (failed count = " + msg.getFailedCount() + ")");
    }

    @Transactional
    @Override
    public void setStateFailed(Message msg, Exception ex, ErrorExtEnum errCode, String customData,
            Map<String, Object> props) {

        Assert.notNull(msg, "the msg must not be null");

        msg.setState(MsgStateEnum.FAILED);
        updateErrorMessage(msg, ex, errCode, customData, props);

        Log.debug("State of the message " + msg.toHumanString() + " was changed to "
                + MsgStateEnum.FAILED + " (failed count = " + msg.getFailedCount() + ")");

        // check parent message with HARD binding - if any
        if (msg.existHardParent()) {
            setParentMsgFailed(msg);
        }
    }

    private void setParentMsgFailed(Message msg) {
        Assert.notNull(msg, "msg must not be null");
        Assert.isTrue(msg.existHardParent(), "only parent message with HARD binding can be affected");

        // mark parent message as failed too
        Message parentMsg = messageDao.getMessage(msg.getParentMsgId());

        parentMsg.setState(MsgStateEnum.FAILED);
        parentMsg.setLastUpdateTimestamp(new Date());

        // set information about error from child message
        parentMsg.setFailedErrorCode(msg.getFailedErrorCode());
        parentMsg.setFailedDesc(msg.getFailedDesc());
        parentMsg.setFailedCount(msg.getFailedCount());

        messageDao.update(parentMsg);

        Log.debug("State of the parent message " + parentMsg.toHumanString() + " was changed to " + MsgStateEnum.FAILED);
    }

    @Transactional
    @Override
    public void setStateFailed(Message msg, ErrorExtEnum errCode, String errDesc) {
        Assert.notNull(msg, "the msg must not be null");
        Assert.notNull(errCode, "the errCode must not be null");
        Assert.hasText(errDesc, "the errDesc must not be empty");

        msg.setState(MsgStateEnum.FAILED);
        msg.setLastUpdateTimestamp(new Date());
        msg.setFailedErrorCode(errCode);
        msg.setFailedCount(msg.getFailedCount() + 1);
        msg.setFailedDesc(errDesc);

        messageDao.update(msg);

        Log.debug("State of the message " + msg.toHumanString() + " was changed to "
                + MsgStateEnum.FAILED + " (failed count = " + msg.getFailedCount() + ")");

        // check parent message with HARD binding - if any
        if (msg.existHardParent()) {
            setParentMsgFailed(msg);
        }
    }

    @Nullable
    @Override
    public Message findMessageById(Long msgId) {
        return messageDao.findMessage(msgId);
    }

    @Nullable
    @Override
    public Message findEagerMessageById(Long msgId) {
        return messageDao.findEagerMessage(msgId);
    }

    @Nullable
    @Override
    public Message findMessageByCorrelationId(String correlationId, @Nullable ExternalSystemExtEnum systemEnum) {
        return messageDao.findByCorrelationId(correlationId, systemEnum);
    }

    private void updateErrorMessage(Message msg, Exception ex, @Nullable ErrorExtEnum errCode,
            @Nullable String customData, Map<String, Object> props) {

        Assert.notNull(msg, "the msg must not be null");
        Assert.notNull(ex, "the ex must not be null");
        Assert.notNull(props, "the props must not be null");

        ErrorExtEnum tmpErrCode = errCode;
        if (tmpErrCode == null) {
            tmpErrCode = ExceptionTranslator.getError(ex);
        }

        msg.setLastUpdateTimestamp(new Date());
        msg.setFailedCount(msg.getFailedCount() + 1);
        msg.setFailedErrorCode(tmpErrCode);

        // save error description also with stack trace
        String errDesc = ExceptionTranslator.composeErrorMessage(tmpErrCode, ex);
        errDesc += "\n";
        errDesc += ExceptionUtils.getStackTrace(ex);

        msg.setFailedDesc(errDesc);

        if (customData != null) {
            msg.setCustomData(customData);
        }

        // move new business errors to message:
        MessageHelper.updateBusinessErrors(msg, props);

        messageDao.update(msg);
    }

    @Override
    public List<Message> findMessagesByContent(String substring) {
        Assert.hasText(substring, "the substring must not be empty");

        return messageDao.findMessagesByContent(substring);
    }

    @Override
    public int getCountMessages(MsgStateEnum state, Integer interval) {
        Assert.notNull(state, "the state must not be null");

        return messageDao.getCountMessages(state, interval);
    }

    @Override
    public int getCountProcessingMessagesForFunnel(String funnelValue, int idleInterval, String funnelCompId) {
        Assert.hasText(funnelValue, "the funnelValue must not be empty");

        return messageDao.getCountProcessingMessagesForFunnel(funnelValue, idleInterval, funnelCompId);
    }

    @Override
    public List<Message> getMessagesForGuaranteedOrderForRoute(String funnelValue, boolean excludeFailedState) {
        return messageDao.getMessagesForGuaranteedOrderForRoute(funnelValue, excludeFailedState);
    }

    @Override
    public List<Message> getMessagesForGuaranteedOrderForFunnel(String funnelValue, int idleInterval,
            boolean excludeFailedState, String funnelCompId) {
        return messageDao.getMessagesForGuaranteedOrderForFunnel(funnelValue, idleInterval, excludeFailedState,
                funnelCompId);
    }

    @Transactional
    @Override
    public void setStatePostponed(Message msg) {
        Assert.notNull(msg, "the msg must not be null");

        Assert.isTrue(msg.getState() == MsgStateEnum.PROCESSING,
                "the message must be in PROCESSING state, but state is " + msg.getState());

        msg.setState(MsgStateEnum.POSTPONED);
        msg.setLastUpdateTimestamp(new Date());

        messageDao.update(msg);

        Log.debug("State of the message " + msg.toHumanString() + " was changed to " + MsgStateEnum.POSTPONED);
    }

    @Transactional
    @Override
    public void setFunnelComponentId(Message msg, String funnelCompId) {
        Assert.notNull(msg, "the msg must not be null");
        Assert.hasText(funnelCompId, "the funnelCompId must not be empty");

        Assert.isTrue(msg.getState() == MsgStateEnum.PROCESSING,
                "the message must be in PROCESSING state, but state is " + msg.getState());

        msg.setFunnelComponentId(funnelCompId);
        msg.setLastUpdateTimestamp(new Date());

        messageDao.update(msg);

        Log.debug("Sets funnel ID of the message " + msg.toHumanString() + " to value: " + funnelCompId);
    }
}
