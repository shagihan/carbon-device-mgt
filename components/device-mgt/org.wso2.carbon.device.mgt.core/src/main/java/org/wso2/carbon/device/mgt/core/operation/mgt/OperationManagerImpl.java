/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.device.mgt.core.operation.mgt;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.device.mgt.common.Device;
import org.wso2.carbon.device.mgt.common.DeviceIdentifier;
import org.wso2.carbon.device.mgt.common.DeviceManagementException;
import org.wso2.carbon.device.mgt.common.EnrolmentInfo;
import org.wso2.carbon.device.mgt.common.InvalidDeviceException;
import org.wso2.carbon.device.mgt.common.MonitoringOperation;
import org.wso2.carbon.device.mgt.common.PaginationRequest;
import org.wso2.carbon.device.mgt.common.PaginationResult;
import org.wso2.carbon.device.mgt.common.TransactionManagementException;
import org.wso2.carbon.device.mgt.common.authorization.DeviceAccessAuthorizationException;
import org.wso2.carbon.device.mgt.common.group.mgt.DeviceGroupConstants;
import org.wso2.carbon.device.mgt.common.operation.mgt.Activity;
import org.wso2.carbon.device.mgt.common.operation.mgt.ActivityStatus;
import org.wso2.carbon.device.mgt.common.operation.mgt.Operation;
import org.wso2.carbon.device.mgt.common.operation.mgt.OperationManagementException;
import org.wso2.carbon.device.mgt.common.operation.mgt.OperationManager;
import org.wso2.carbon.device.mgt.common.push.notification.NotificationContext;
import org.wso2.carbon.device.mgt.common.push.notification.NotificationStrategy;
import org.wso2.carbon.device.mgt.common.push.notification.PushNotificationConfig;
import org.wso2.carbon.device.mgt.common.push.notification.PushNotificationExecutionFailedException;
import org.wso2.carbon.device.mgt.common.push.notification.PushNotificationProvider;
import org.wso2.carbon.device.mgt.common.spi.DeviceManagementService;
import org.wso2.carbon.device.mgt.core.DeviceManagementConstants;
import org.wso2.carbon.device.mgt.core.config.DeviceConfigurationManager;
import org.wso2.carbon.device.mgt.core.dao.DeviceDAO;
import org.wso2.carbon.device.mgt.core.dao.DeviceManagementDAOException;
import org.wso2.carbon.device.mgt.core.dao.DeviceManagementDAOFactory;
import org.wso2.carbon.device.mgt.core.dao.EnrollmentDAO;
import org.wso2.carbon.device.mgt.core.internal.DeviceManagementDataHolder;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.OperationDAO;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.OperationManagementDAOException;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.OperationManagementDAOFactory;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.OperationMappingDAO;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.util.OperationDAOUtil;
import org.wso2.carbon.device.mgt.core.operation.mgt.util.DeviceIDHolder;
import org.wso2.carbon.device.mgt.core.operation.mgt.util.OperationIdComparator;
import org.wso2.carbon.device.mgt.core.service.DeviceManagementProviderService;
import org.wso2.carbon.device.mgt.core.task.DeviceTaskManager;
import org.wso2.carbon.device.mgt.core.task.impl.DeviceTaskManagerImpl;
import org.wso2.carbon.device.mgt.core.util.DeviceManagerUtil;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class implements all the functionality exposed as part of the OperationManager. Any transaction initiated
 * upon persisting information related to operation state, etc has to be managed, demarcated and terminated via the
 * methods available in OperationManagementDAOFactory.
 */
public class OperationManagerImpl implements OperationManager {

    private static final Log log = LogFactory.getLog(OperationManagerImpl.class);
    private static final int CACHE_VALIDITY_PERIOD = 5 * 60 * 1000;
    private static final String NOTIFIER_TYPE_LOCAL = "LOCAL";
    private static final String SYSTEM = "system";

    private OperationDAO commandOperationDAO;
    private OperationDAO configOperationDAO;
    private OperationDAO profileOperationDAO;
    private OperationDAO policyOperationDAO;
    private OperationMappingDAO operationMappingDAO;
    private OperationDAO operationDAO;
    private DeviceDAO deviceDAO;
    private EnrollmentDAO enrollmentDAO;
    private String deviceType;
    private DeviceManagementService deviceManagementService;
    private Map<Integer, NotificationStrategy> notificationStrategies;
    private Map<Integer, Long> lastUpdatedTimeStamps;

    public OperationManagerImpl() {
        commandOperationDAO = OperationManagementDAOFactory.getCommandOperationDAO();
        configOperationDAO = OperationManagementDAOFactory.getConfigOperationDAO();
        profileOperationDAO = OperationManagementDAOFactory.getProfileOperationDAO();
        policyOperationDAO = OperationManagementDAOFactory.getPolicyOperationDAO();
        operationMappingDAO = OperationManagementDAOFactory.getOperationMappingDAO();
        operationDAO = OperationManagementDAOFactory.getOperationDAO();
        deviceDAO = DeviceManagementDAOFactory.getDeviceDAO();
        enrollmentDAO = DeviceManagementDAOFactory.getEnrollmentDAO();
        notificationStrategies = new HashMap<>();
        lastUpdatedTimeStamps = new HashMap<>();
    }

    public OperationManagerImpl(String deviceType, DeviceManagementService deviceManagementService) {
        this();
        this.deviceType = deviceType;
        this.deviceManagementService = deviceManagementService;
    }

    public NotificationStrategy getNotificationStrategy() {
        // Notification strategy can be set by the platform configurations. Therefore it is needed to
        // get tenant specific notification strategy dynamically in the runtime. However since this is
        // a resource intensive retrieval, we are maintaining tenant aware local cache here to keep device
        // type specific notification strategy.
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId(false);
        long lastUpdatedTimeStamp = 0;
        if (lastUpdatedTimeStamps.containsKey(tenantId)){
            lastUpdatedTimeStamp = lastUpdatedTimeStamps.get(tenantId);
        }
        if (Calendar.getInstance().getTimeInMillis() - lastUpdatedTimeStamp > CACHE_VALIDITY_PERIOD) {
            PushNotificationConfig pushNoteConfig = deviceManagementService.getPushNotificationConfig();
            if (pushNoteConfig != null && !NOTIFIER_TYPE_LOCAL.equals(pushNoteConfig.getType())) {
                PushNotificationProvider provider = DeviceManagementDataHolder.getInstance()
                        .getPushNotificationProviderRepository().getProvider(pushNoteConfig.getType());
                if (provider == null) {
                    log.error("No registered push notification provider found for the type '" +
                              pushNoteConfig.getType() + "' under tenant ID '" + tenantId + "'.");
                    return null;
                }
                notificationStrategies.put(tenantId, provider.getNotificationStrategy(pushNoteConfig));
            } else if (notificationStrategies.containsKey(tenantId)){
                notificationStrategies.remove(tenantId);
            }
            lastUpdatedTimeStamps.put(tenantId, Calendar.getInstance().getTimeInMillis());
        }
        return notificationStrategies.get(tenantId);
    }

    @Override
    public Activity addOperation(Operation operation,
                                 List<DeviceIdentifier> deviceIds)
            throws OperationManagementException, InvalidDeviceException {
        if (log.isDebugEnabled()) {
            log.debug("operation:[" + operation.toString() + "]");
            for (DeviceIdentifier deviceIdentifier : deviceIds) {
                log.debug("device identifier id:[" + deviceIdentifier.getId() + "] type:[" +
                        deviceIdentifier.getType() + "]");
            }
        }
        try {
            DeviceIDHolder deviceValidationResult = DeviceManagerUtil.validateDeviceIdentifiers(deviceIds);
            List<DeviceIdentifier> validDeviceIds = deviceValidationResult.getValidDeviceIDList();
            if (validDeviceIds.size() > 0) {
                DeviceIDHolder deviceAuthorizationResult = this.authorizeDevices(operation, validDeviceIds);
                List<DeviceIdentifier> authorizedDeviceIds = deviceAuthorizationResult.getValidDeviceIDList();
                if (authorizedDeviceIds.size() <= 0) {
                    log.warn("User : " + getUser() + " is not authorized to perform operations on given device-list.");
                    Activity activity = new Activity();
                    //Send the operation statuses only for admin triggered operations
                    String deviceType = validDeviceIds.get(0).getType();
                    activity.setActivityStatus(this.getActivityStatus(deviceValidationResult, deviceAuthorizationResult,
                            deviceType));
                    return activity;
                }

                boolean isScheduledOperation = this.isTaskScheduledOperation(operation);
                String initiatedBy = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
                if (initiatedBy == null && isScheduledOperation) {
                    if(log.isDebugEnabled()) {
                        log.debug("initiatedBy : "  + SYSTEM);
                    }
                    operation.setInitiatedBy(SYSTEM);
                } else {
                    if(log.isDebugEnabled()) {
                        log.debug("initiatedBy : "  + initiatedBy);
                    }
                    operation.setInitiatedBy(initiatedBy);
                }

                OperationManagementDAOFactory.beginTransaction();
                org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation operationDto =
                        OperationDAOUtil.convertOperation(operation);
                int enrolmentId;
                String operationCode = operationDto.getCode();

                List<Device> authorizedDevices = new ArrayList<>();
                List<Device> ignoredDevices = new ArrayList<>();
                for (DeviceIdentifier deviceId : authorizedDeviceIds) {
                    Device device = getDevice(deviceId);
                    authorizedDevices.add(device);
                }

                if (operationDto.getControl() ==
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Control.NO_REPEAT) {
                    int existingOperationID;
                    for (Device device : authorizedDevices) {
                        enrolmentId = device.getEnrolmentInfo().getId();
                        existingOperationID = operationDAO.getExistingOperationID(enrolmentId, operationCode);
                        if (existingOperationID > 0) {
                            ignoredDevices.add(device);
                            operation.setId(existingOperationID);
                            this.sendNotification(operation, device);
                        }
                    }
                }

                if (ignoredDevices.size() > 0) {
                    if (authorizedDevices.size() == ignoredDevices.size()) {
                        if (log.isDebugEnabled()) {
                            log.debug("All the devices contain a pending operation for the Operation Code: "
                                    + operationCode);
                        }
                        Activity activity = new Activity();
                        //Send the operation statuses only for admin triggered operations
                        String deviceType = validDeviceIds.get(0).getType();
                        activity.setActivityStatus(this.getActivityStatus(deviceValidationResult, deviceAuthorizationResult,
                                deviceType));
                        return activity;
                    } else {
                        authorizedDevices.removeAll(ignoredDevices);
                    }
                }

                int operationId = this.lookupOperationDAO(operation).addOperation(operationDto);

                boolean isScheduled = false;
                NotificationStrategy notificationStrategy = getNotificationStrategy();

                // check whether device list is greater than batch size notification strategy has enable to send push
                // notification using scheduler task
                if (DeviceConfigurationManager.getInstance().getDeviceManagementConfig().
                        getPushNotificationConfiguration().getSchedulerBatchSize() <= authorizedDeviceIds.size() &&
                        notificationStrategy != null) {
                    isScheduled = notificationStrategy.getConfig().isScheduled();
                }

                //TODO have to create a sql to load device details from deviceDAO using single query.
                for (Device device : authorizedDevices) {
                    enrolmentId = device.getEnrolmentInfo().getId();
                    //Do not repeat the task operations
                    operationMappingDAO.addOperationMapping(operationId, enrolmentId, isScheduled);
                }
                OperationManagementDAOFactory.commitTransaction();

                if (!isScheduled) {
                    for (Device device : authorizedDevices) {
                        this.sendNotification(operation, device);
                    }
                }

                Activity activity = new Activity();
                activity.setActivityId(DeviceManagementConstants.OperationAttributes.ACTIVITY + operationId);
                activity.setCode(operationCode);
                activity.setCreatedTimeStamp(new Date().toString());
                activity.setType(Activity.Type.valueOf(operationDto.getType().toString()));
                //For now set the operation statuses only for admin triggered operations
                if (!isScheduledOperation) {
                    //Get the device-type from 1st valid DeviceIdentifier. We know the 1st element is definitely there.
                    String deviceType = validDeviceIds.get(0).getType();
                    activity.setActivityStatus(this.getActivityStatus(deviceValidationResult, deviceAuthorizationResult,
                            deviceType));
                }
                return activity;
            } else {
                throw new InvalidDeviceException("Invalid device Identifiers found.");
            }
        } catch (OperationManagementDAOException e) {
            OperationManagementDAOFactory.rollbackTransaction();
            throw new OperationManagementException("Error occurred while adding operation", e);
        } catch (TransactionManagementException e) {
            throw new OperationManagementException("Error occurred while initiating the transaction", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    private void sendNotification(Operation operation, Device device) {
        NotificationStrategy notificationStrategy = getNotificationStrategy();
        /*
         * If notification strategy has not enable to send push notification using scheduler task we will send
         * notification immediately. This is done in separate loop inorder to prevent overlap with DB insert
         * operations with the possible db update operations trigger followed by pending operation call.
         * Otherwise device may call pending operation while DB is locked for write and deadlock can occur.
         */
        if (notificationStrategy != null) {
            if (log.isDebugEnabled()) {
                log.debug("Sending push notification to " + device.getDeviceIdentifier() + " from add operation method.");
            }
            DeviceIdentifier deviceIdentifier = new DeviceIdentifier(device.getDeviceIdentifier(), device.getType());
            try {
                notificationStrategy.execute(new NotificationContext(deviceIdentifier, operation));
            } catch (PushNotificationExecutionFailedException e) {
                log.error("Error occurred while sending push notifications to " + device.getType() +
                          " device carrying id '" + device.getDeviceIdentifier() + "'", e);
                /*
                 * Reschedule if push notification failed. Doing db transactions in atomic way to prevent
                 * deadlocks.
                 */
                try {
                    operationMappingDAO.updateOperationMapping(operation.getId(), device.getEnrolmentInfo().getId(), org.wso2.carbon
                            .device.mgt.core.dto.operation.mgt.Operation.PushNotificationStatus.SCHEDULED);
                    OperationManagementDAOFactory.commitTransaction();
                } catch (OperationManagementDAOException ex) {
                    // Not throwing this exception in order to keep sending remaining notifications if any.
                    log.error("Error occurred while setting push notification status to SCHEDULED.", ex);
                    OperationManagementDAOFactory.rollbackTransaction();
                }
            }
        }
    }

    private List<ActivityStatus> getActivityStatus(DeviceIDHolder deviceIdValidationResult, DeviceIDHolder deviceAuthResult,
                                                   String deviceType) {
        List<ActivityStatus> activityStatuses = new ArrayList<>();
        ActivityStatus activityStatus;
        //Add the invalid DeviceIds
        for (String id : deviceIdValidationResult.getErrorDeviceIdList()) {
            activityStatus = new ActivityStatus();
            activityStatus.setDeviceIdentifier(new DeviceIdentifier(id, deviceType));
            activityStatus.setStatus(ActivityStatus.Status.INVALID);
            activityStatuses.add(activityStatus);
        }

        //Add the unauthorized DeviceIds
        for (String id : deviceAuthResult.getErrorDeviceIdList()) {
            activityStatus = new ActivityStatus();
            activityStatus.setDeviceIdentifier(new DeviceIdentifier(id, deviceType));
            activityStatus.setStatus(ActivityStatus.Status.UNAUTHORIZED);
            activityStatuses.add(activityStatus);
        }

        //Add the authorized DeviceIds
        for (DeviceIdentifier id : deviceAuthResult.getValidDeviceIDList()) {
            activityStatus = new ActivityStatus();
            activityStatus.setDeviceIdentifier(id);
            activityStatus.setStatus(ActivityStatus.Status.PENDING);
            activityStatuses.add(activityStatus);
        }
        return activityStatuses;
    }

    private DeviceIDHolder authorizeDevices(
            Operation operation, List<DeviceIdentifier> deviceIds) throws OperationManagementException {
        List<DeviceIdentifier> authorizedDeviceList;
        List<String> unAuthorizedDeviceList = new ArrayList<>();
        DeviceIDHolder deviceIDHolder = new DeviceIDHolder();
        try {
            if (operation != null && isAuthenticationSkippedOperation(operation)) {
                authorizedDeviceList = deviceIds;
            } else {
                boolean isAuthorized;
                authorizedDeviceList = new ArrayList<>();
                for (DeviceIdentifier devId : deviceIds) {
                    isAuthorized = DeviceManagementDataHolder.getInstance().getDeviceAccessAuthorizationService().
                            isUserAuthorized(devId);
                    if (isAuthorized) {
                        authorizedDeviceList.add(devId);
                    } else {
                        unAuthorizedDeviceList.add(devId.getId());
                    }
                }
            }
        } catch (DeviceAccessAuthorizationException e) {
            throw new OperationManagementException("Error occurred while authorizing access to the devices for user :" +
                    this.getUser(), e);
        }
        deviceIDHolder.setValidDeviceIDList(authorizedDeviceList);
        deviceIDHolder.setErrorDeviceIdList(unAuthorizedDeviceList);
        return deviceIDHolder;
    }

    private Device getDevice(DeviceIdentifier deviceId) throws OperationManagementException {
        try {
            return DeviceManagementDataHolder.getInstance().getDeviceManagementProvider().getDevice(deviceId, false);
        } catch (DeviceManagementException e) {
            throw new OperationManagementException(
                    "Error occurred while retrieving device info.", e);
        }
    }

    @Override
    public List<? extends Operation> getOperations(DeviceIdentifier deviceId) throws OperationManagementException {
        List<Operation> operations = null;

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                    deviceId.getType() + "' device, which carries the identifier '" +
                    deviceId.getId() + "'");
        }

        EnrolmentInfo enrolmentInfo = this.getActiveEnrolmentInfo(deviceId);
        if (enrolmentInfo == null) {
            return null;
        }

        try {
            OperationManagementDAOFactory.openConnection();
            List<? extends org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation> operationList =
                    operationDAO.getOperationsForDevice(enrolmentInfo.getId());

            operations = new ArrayList<>();
            for (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation : operationList) {
                Operation operation = OperationDAOUtil.convertOperation(dtoOperation);
                operations.add(operation);
            }
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the list of " +
                    "operations assigned for '" + deviceId.getType() +
                    "' device '" + deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
        return operations;
    }

    @Override
    public PaginationResult getOperations(DeviceIdentifier deviceId, PaginationRequest request)
            throws OperationManagementException {
        PaginationResult paginationResult = null;
        List<Operation> operations = new ArrayList<>();
        String owner = request.getOwner();
        try {
            if (!DeviceManagerUtil.isDeviceExists(deviceId)) {
                throw new OperationManagementException("Device not found for given device " +
                        "Identifier:" + deviceId.getId() + " and given type : " +
                        deviceId.getType());
            }
        } catch (DeviceManagementException e) {
            throw new OperationManagementException("Error while checking the existence of the device identifier - "
                    + deviceId.getId() + " of the device type - " + deviceId.getType(), e);
        }
        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                    deviceId.getType() + "' device, which carries the identifier '" +
                    deviceId.getId() + "' of owner '" + owner + "'");
        }
        EnrolmentInfo enrolmentInfo = this.getEnrolmentInfo(deviceId, owner);
        int enrolmentId = enrolmentInfo.getId();
        try {
            OperationManagementDAOFactory.openConnection();
            List<? extends org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation> operationList =
                    operationDAO.getOperationsForDevice(enrolmentId, request);
            for (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation : operationList) {
                Operation operation = OperationDAOUtil.convertOperation(dtoOperation);
                operations.add(operation);
            }
            paginationResult = new PaginationResult();
            int count = operationDAO.getOperationCountForDevice(enrolmentId);
            paginationResult.setData(operations);
            paginationResult.setRecordsTotal(count);
            paginationResult.setRecordsFiltered(count);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the list of " +
                    "operations assigned for '" + deviceId.getType() +
                    "' device '" + deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }

        return paginationResult;
    }

    @Override
    public List<? extends Operation> getPendingOperations(DeviceIdentifier deviceId) throws
            OperationManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Device identifier id:[" + deviceId.getId() + "] type:[" + deviceId.getType() + "]");
        }
        List<Operation> operations = new ArrayList<>();
        List<org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation> dtoOperationList = new ArrayList<>();

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                    deviceId.getType() + "' device, which carries the identifier '" +
                    deviceId.getId() + "'");
        }

        //
        EnrolmentInfo enrolmentInfo = this.getActiveEnrolmentInfo(deviceId);
        if (enrolmentInfo == null) {
            throw new OperationManagementException("Device not found for the given device Identifier:" +
                    deviceId.getId() + " and given type:" +
                    deviceId.getType());
        }
        int enrolmentId = enrolmentInfo.getId();
        //Changing the enrollment status & attempt count if the device is marked as inactive or unreachable
        switch (enrolmentInfo.getStatus()) {
            case INACTIVE:
            case UNREACHABLE:
                this.setEnrolmentStatus(enrolmentId, EnrolmentInfo.Status.ACTIVE);
                break;
        }

        try {
            OperationManagementDAOFactory.openConnection();
            dtoOperationList.addAll(commandOperationDAO.getOperationsByDeviceAndStatus(
                    enrolmentId, org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING));
            dtoOperationList.addAll(configOperationDAO.getOperationsByDeviceAndStatus(
                    enrolmentId, org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING));
            dtoOperationList.addAll(profileOperationDAO.getOperationsByDeviceAndStatus(
                    enrolmentId, org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING));
            dtoOperationList.addAll(policyOperationDAO.getOperationsByDeviceAndStatus(
                    enrolmentId, org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING));
            Operation operation;
            for (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation : dtoOperationList) {
                operation = OperationDAOUtil.convertOperation(dtoOperation);
                operations.add(operation);
            }
            Collections.sort(operations, new OperationIdComparator());
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the list of " +
                    "pending operations assigned for '" + deviceId.getType() +
                    "' device '" + deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
        return operations;
    }

    @Override
    public Operation getNextPendingOperation(DeviceIdentifier deviceId) throws OperationManagementException {
        // setting notNowOperationFrequency to -1 to avoid picking notnow operations
        return this.getNextPendingOperation(deviceId, -1);
    }

    @Override
    public Operation getNextPendingOperation(DeviceIdentifier deviceId, long notNowOperationFrequency)
            throws OperationManagementException {
        if (log.isDebugEnabled()) {
            log.debug("device identifier id:[" + deviceId.getId() + "] type:[" + deviceId.getType() + "]");
        }
        Operation operation = null;

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                    deviceId.getType() + "' device, which carries the identifier '" +
                    deviceId.getId() + "'");
        }

        EnrolmentInfo enrolmentInfo = this.getActiveEnrolmentInfo(deviceId);
        if (enrolmentInfo == null) {
            throw new OperationManagementException("Device not found for given device " +
                    "Identifier:" + deviceId.getId() + " and given type" +
                    deviceId.getType());
        }
        int enrolmentId = enrolmentInfo.getId();
        //Changing the enrollment status & attempt count if the device is marked as inactive or unreachable
        switch (enrolmentInfo.getStatus()) {
            case INACTIVE:
            case UNREACHABLE:
                this.setEnrolmentStatus(enrolmentId, EnrolmentInfo.Status.ACTIVE);
                break;
        }

        try {
            OperationManagementDAOFactory.openConnection();
            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation = null;

            // check whether notnow is set
            if (notNowOperationFrequency > 0) {
                // retrieve Notnow operations
                dtoOperation = operationDAO.getNextOperation(enrolmentInfo.getId(),
                        org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.NOTNOW);
            }

            if (dtoOperation != null) {
                long currentTime = Calendar.getInstance().getTime().getTime();
                log.info("Current timestamp:" + currentTime);
                long updatedTime = Timestamp.valueOf(dtoOperation.getReceivedTimeStamp()).getTime();
                log.info("Updated timestamp: " + updatedTime);

                // check if notnow frequency is met and set next pending operation if not, otherwise let notnow
                // operation to proceed
                if ((currentTime - updatedTime) < notNowOperationFrequency) {
                    dtoOperation = operationDAO.getNextOperation(enrolmentInfo.getId(),
                            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING);
                }
            } else {
                dtoOperation = operationDAO.getNextOperation(enrolmentInfo.getId(),
                        org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.PENDING);
            }

            if (dtoOperation != null) {
                if (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.COMMAND.equals(dtoOperation.getType()
                )) {
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation commandOperation;
                    commandOperation =
                            (org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation) commandOperationDAO.
                                    getOperation(dtoOperation.getId());
                    dtoOperation.setEnabled(commandOperation.isEnabled());
                } else if (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.CONFIG.equals(dtoOperation.
                        getType())) {
                    dtoOperation = configOperationDAO.getOperation(dtoOperation.getId());
                } else if (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.PROFILE.equals(dtoOperation.
                        getType())) {
                    dtoOperation = profileOperationDAO.getOperation(dtoOperation.getId());
                } else if (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.POLICY.equals(dtoOperation.
                        getType())) {
                    dtoOperation = policyOperationDAO.getOperation(dtoOperation.getId());
                }
                operation = OperationDAOUtil.convertOperation(dtoOperation);
            }
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving next pending operation", e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
        return operation;
    }

    @Override
    public void updateOperation(DeviceIdentifier deviceId, Operation operation) throws OperationManagementException {
        int operationId = operation.getId();
        if (log.isDebugEnabled()) {
            log.debug("operation Id:" + operationId + " status:" + operation.getStatus());
        }

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                    deviceId.getType() + "' device, which carries the identifier '" +
                    deviceId.getId() + "'");
        }

        EnrolmentInfo enrolmentInfo = this.getActiveEnrolmentInfo(deviceId);
        if (enrolmentInfo == null) {
            throw new OperationManagementException(
                    "Device not found for device id:" + deviceId.getId() + " " + "type:" +
                            deviceId.getType());
        }

        try {
            int enrolmentId = enrolmentInfo.getId();
            OperationManagementDAOFactory.beginTransaction();
            if (operation.getStatus() != null) {
                 operationDAO.updateOperationStatus(enrolmentId, operationId,
                        org.wso2.carbon.device.mgt.core.dto.operation.mgt.
                                Operation.Status.valueOf(operation.getStatus().
                                toString()));
            }
            if (operation.getOperationResponse() != null) {
                operationDAO.addOperationResponse(enrolmentId, operationId, operation.getOperationResponse());
            }
            OperationManagementDAOFactory.commitTransaction();
        } catch (OperationManagementDAOException e) {
            OperationManagementDAOFactory.rollbackTransaction();
            throw new OperationManagementException(
                    "Error occurred while updating the operation: " + operationId + " status:" +
                            operation.getStatus(), e);
        } catch (TransactionManagementException e) {
            throw new OperationManagementException("Error occurred while initiating a transaction", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public Operation getOperationByDeviceAndOperationId(DeviceIdentifier deviceId, int operationId)
            throws OperationManagementException {
        Operation operation = null;
        if (log.isDebugEnabled()) {
            log.debug("Operation Id: " + operationId + " Device Type: " + deviceId.getType() + " Device Identifier: " +
                    deviceId.getId());
        }

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                    deviceId.getType() + "' device, which carries the identifier '" +
                    deviceId.getId() + "'");
        }

        EnrolmentInfo enrolmentInfo = this.getActiveEnrolmentInfo(deviceId);
        if (enrolmentInfo == null) {
            throw new OperationManagementException("Device not found for given device identifier: " +
                    deviceId.getId() + " type: " + deviceId.getType());
        }

        try {
            OperationManagementDAOFactory.openConnection();
            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation deviceSpecificOperation = operationDAO.
                    getOperationByDeviceAndId(enrolmentInfo.getId(),
                            operationId);
            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation = deviceSpecificOperation;
            if (deviceSpecificOperation.getType().
                    equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.COMMAND)) {
                org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation commandOperation;
                commandOperation =
                        (org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation) commandOperationDAO.
                                getOperation(deviceSpecificOperation.getId());
                dtoOperation.setEnabled(commandOperation.isEnabled());
            } else if (deviceSpecificOperation.getType().
                    equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.CONFIG)) {
                dtoOperation = configOperationDAO.getOperation(deviceSpecificOperation.getId());
            } else if (deviceSpecificOperation.getType().equals(
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.PROFILE)) {
                dtoOperation = profileOperationDAO.getOperation(deviceSpecificOperation.getId());
            } else if (deviceSpecificOperation.getType().equals(
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.POLICY)) {
                dtoOperation = policyOperationDAO.getOperation(deviceSpecificOperation.getId());
            }
            if (dtoOperation == null) {
                throw new OperationManagementException("Operation not found for operation Id:" + operationId +
                        " device id:" + deviceId.getId());
            }
            dtoOperation.setStatus(deviceSpecificOperation.getStatus());
            operation = OperationDAOUtil.convertOperation(deviceSpecificOperation);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the list of " +
                    "operations assigned for '" + deviceId.getType() +
                    "' device '" + deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening connection to the data source",
                    e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }

        return operation;
    }

    @Override
    public List<? extends Operation> getOperationsByDeviceAndStatus(
            DeviceIdentifier deviceId, Operation.Status status) throws OperationManagementException {
        List<Operation> operations = new ArrayList<>();
        List<org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation> dtoOperationList = new ArrayList<>();

        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                    deviceId.getType() + "' device, which carries the identifier '" +
                    deviceId.getId() + "'");
        }

        EnrolmentInfo enrolmentInfo = this.getActiveEnrolmentInfo(deviceId);
        if (enrolmentInfo == null) {
            throw new OperationManagementException(
                    "Device not found for device id:" + deviceId.getId() + " " + "type:" +
                            deviceId.getType());
        }

        try {
            int enrolmentId = enrolmentInfo.getId();
            OperationManagementDAOFactory.openConnection();
            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status dtoOpStatus =
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Status.valueOf(status.toString());
            dtoOperationList.addAll(commandOperationDAO.getOperationsByDeviceAndStatus(enrolmentId, dtoOpStatus));
            dtoOperationList.addAll(configOperationDAO.getOperationsByDeviceAndStatus(enrolmentId,
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.
                            Status.PENDING));
            dtoOperationList.addAll(profileOperationDAO.getOperationsByDeviceAndStatus(enrolmentId,
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.
                            Status.PENDING));
            dtoOperationList.addAll(policyOperationDAO.getOperationsByDeviceAndStatus(enrolmentId,
                    org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.
                            Status.PENDING));

            Operation operation;

            for (org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation : dtoOperationList) {
                operation = OperationDAOUtil.convertOperation(dtoOperation);
                operations.add(operation);
            }

        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the list of " +
                    "operations assigned for '" + deviceId.getType() +
                    "' device '" +
                    deviceId.getId() + "' and status:" + status.toString(), e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
        return operations;
    }

    @Override
    public Operation getOperation(int operationId) throws OperationManagementException {
        Operation operation;
        try {
            OperationManagementDAOFactory.openConnection();
            org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation dtoOperation = operationDAO.getOperation(
                    operationId);
            if (dtoOperation == null) {
                throw new OperationManagementException("Operation not found for given Id:" + operationId);
            }

            if (dtoOperation.getType()
                    .equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.COMMAND)) {
                org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation commandOperation;
                commandOperation =
                        (org.wso2.carbon.device.mgt.core.dto.operation.mgt.CommandOperation) commandOperationDAO.
                                getOperation(dtoOperation.getId());
                dtoOperation.setEnabled(commandOperation.isEnabled());
            } else if (dtoOperation.getType().
                    equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.CONFIG)) {
                dtoOperation = configOperationDAO.getOperation(dtoOperation.getId());
            } else if (dtoOperation.getType().equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.
                    PROFILE)) {
                dtoOperation = profileOperationDAO.getOperation(dtoOperation.getId());
            } else if (dtoOperation.getType().equals(org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation.Type.
                    POLICY)) {
                dtoOperation = policyOperationDAO.getOperation(dtoOperation.getId());
            }
            operation = OperationDAOUtil.convertOperation(dtoOperation);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the operation with operation Id '" +
                    operationId, e);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
        return operation;
    }

    @Override
    public Activity getOperationByActivityId(String activity) throws OperationManagementException {
        // This parses the operation id from activity id (ex : ACTIVITY_23) and converts to the integer.
        int operationId = Integer.parseInt(
                activity.replace(DeviceManagementConstants.OperationAttributes.ACTIVITY, ""));
        if (operationId == 0) {
            throw new IllegalArgumentException("Operation ID cannot be null or zero (0).");
        }
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivity(operationId);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the operation with activity Id '" +
                    activity, e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public List<Activity> getOperationByActivityIds(List<String> activities)
            throws OperationManagementException {
        List<Integer> operationIds = new ArrayList<>();
        for (String id : activities) {
            int operationId = Integer.parseInt(
                    id.replace(DeviceManagementConstants.OperationAttributes.ACTIVITY, ""));
            if (operationId == 0) {
                throw new IllegalArgumentException("Operation ID cannot be null or zero (0).");
            } else {
                operationIds.add(operationId);
            }
        }

        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivityList(operationIds);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException(
                    "Error occurred while retrieving the operation with activity Id '" + activities
                            .toString(), e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    public Activity getOperationByActivityIdAndDevice(String activity, DeviceIdentifier deviceId) throws OperationManagementException {
        // This parses the operation id from activity id (ex : ACTIVITY_23) and converts to the integer.
        int operationId = Integer.parseInt(
                activity.replace(DeviceManagementConstants.OperationAttributes.ACTIVITY, ""));
        if (operationId == 0) {
            throw new IllegalArgumentException("Operation ID cannot be null or zero (0).");
        }
        if (!isActionAuthorized(deviceId)) {
            throw new OperationManagementException("User '" + getUser() + "' is not authorized to access the '" +
                    deviceId.getType() + "' device, which carries the identifier '" +
                    deviceId.getId() + "'");
        }
        Device device = this.getDevice(deviceId);
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivityByDevice(operationId, device.getId());
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving the operation with activity Id '" +
                    activity + " and device Id: " + deviceId.getId(), e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public List<Activity> getActivitiesUpdatedAfter(long timestamp, int limit,
                                                    int offset) throws OperationManagementException {
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivitiesUpdatedAfter(timestamp, limit, offset);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while getting the activity list changed after a " +
                    "given time.", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }


    @Override
    public List<Activity> getFilteredActivities(String operationCode, int limit, int offset) throws OperationManagementException{
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getFilteredActivities(operationCode, limit, offset);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while getting the activity list for the given "
                    + "given operationCode: " + operationCode, e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public int getTotalCountOfFilteredActivities(String operationCode) throws  OperationManagementException{
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getTotalCountOfFilteredActivities(operationCode);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while getting the activity count for the given "
                    + "operation code:" + operationCode, e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public List<Activity> getActivitiesUpdatedAfterByUser(long timestamp, String user, int limit, int offset)
            throws OperationManagementException {
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivitiesUpdatedAfterByUser(timestamp, user, limit, offset);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while getting the activity list changed after a " +
                    "given time which are added by user : " + user, e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public int getActivityCountUpdatedAfter(long timestamp) throws OperationManagementException {
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivityCountUpdatedAfter(timestamp);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while getting the activity count changed after a " +
                    "given time.", e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public int getActivityCountUpdatedAfterByUser(long timestamp, String user) throws OperationManagementException {
        try {
            OperationManagementDAOFactory.openConnection();
            return operationDAO.getActivityCountUpdatedAfterByUser(timestamp, user);
        } catch (SQLException e) {
            throw new OperationManagementException("Error occurred while opening a connection to the data source.", e);
        } catch (OperationManagementDAOException e) {
            throw new OperationManagementException("Error occurred while getting the activity count changed after a " +
                    "given time which are added by user :" + user, e);
        } finally {
            OperationManagementDAOFactory.closeConnection();
        }
    }

    private OperationDAO lookupOperationDAO(Operation operation) {

        if (operation instanceof CommandOperation) {
            return commandOperationDAO;
        } else if (operation instanceof ProfileOperation) {
            return profileOperationDAO;
        } else if (operation instanceof ConfigOperation) {
            return configOperationDAO;
        } else if (operation instanceof PolicyOperation) {
            return policyOperationDAO;
        } else {
            return operationDAO;
        }
    }

    private String getUser() {
        return CarbonContext.getThreadLocalCarbonContext().getUsername();
    }

    private boolean isAuthenticationSkippedOperation(Operation operation) {

        //This is to check weather operations are coming from the task related to retrieving device information.
        DeviceTaskManager taskManager = new DeviceTaskManagerImpl(deviceType);
        if (taskManager.isTaskOperation(operation.getCode())) {
            return true;
        }

        boolean status;
        switch (operation.getCode()) {
            case DeviceManagementConstants.AuthorizationSkippedOperationCodes.POLICY_OPERATION_CODE:
                status = true;
                break;
            case DeviceManagementConstants.AuthorizationSkippedOperationCodes.MONITOR_OPERATION_CODE:
                status = true;
                break;
            case DeviceManagementConstants.AuthorizationSkippedOperationCodes.POLICY_REVOKE_OPERATION_CODE:
                status = true;
                break;
            default:
                status = false;
        }

        return status;
    }

    private boolean isActionAuthorized(DeviceIdentifier deviceId) {
        boolean isUserAuthorized;
        try {
            isUserAuthorized = DeviceManagementDataHolder.getInstance().getDeviceAccessAuthorizationService().
                    isUserAuthorized(deviceId, DeviceGroupConstants.Permissions.DEFAULT_OPERATOR_PERMISSIONS);
        } catch (DeviceAccessAuthorizationException e) {
            log.error("Error occurred while trying to authorize current user upon the invoked operation", e);
            return false;
        }
        return isUserAuthorized;
    }

    private EnrolmentInfo getEnrolmentInfo(DeviceIdentifier deviceId, String owner) throws OperationManagementException {
        EnrolmentInfo enrolmentInfo = null;
        try {
            int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
            String user = this.getUser();
            DeviceManagementDAOFactory.openConnection();
            if (this.isSameUser(user, owner)) {
                enrolmentInfo = deviceDAO.getEnrolment(deviceId, owner, tenantId);
            } else {
                boolean isAdminUser = DeviceManagementDataHolder.getInstance().getDeviceAccessAuthorizationService().
                        isDeviceAdminUser();
                if (isAdminUser) {
                    enrolmentInfo = deviceDAO.getEnrolment(deviceId, owner, tenantId);
                }
                //TODO : Add a check for group admin if this fails
            }
        } catch (DeviceManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving enrollment data of '" +
                    deviceId.getType() + "' device carrying the identifier '" +
                    deviceId.getId() + "' of owner '" + owner + "'", e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } catch (DeviceAccessAuthorizationException e) {
            throw new OperationManagementException("Error occurred while checking the device access permissions for '" +
                    deviceId.getType() + "' device carrying the identifier '" +
                    deviceId.getId() + "' of owner '" + owner + "'", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        return enrolmentInfo;
    }

    private EnrolmentInfo getActiveEnrolmentInfo(DeviceIdentifier deviceId) throws OperationManagementException {
        EnrolmentInfo enrolmentInfo;
        try {
            DeviceManagementDAOFactory.openConnection();
            int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
            enrolmentInfo = deviceDAO.getActiveEnrolment(deviceId, tenantId);
        } catch (DeviceManagementDAOException e) {
            throw new OperationManagementException("Error occurred while retrieving enrollment data of '" +
                    deviceId.getType() + "' device carrying the identifier '" +
                    deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new OperationManagementException(
                    "Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        return enrolmentInfo;
    }

    private boolean setEnrolmentStatus(int enrolmentId, EnrolmentInfo.Status status) throws OperationManagementException {
        boolean updateStatus;
        try {
            DeviceManagementDAOFactory.beginTransaction();
            int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
            String user = this.getUser();
            updateStatus = enrollmentDAO.setStatus(enrolmentId, user, status, tenantId);
            DeviceManagementDAOFactory.commitTransaction();
        } catch (DeviceManagementDAOException e) {
            DeviceManagementDAOFactory.rollbackTransaction();
            throw new OperationManagementException("Error occurred while updating enrollment status of device of " +
                    "enrolment-id '" + enrolmentId + "'", e);
        } catch (TransactionManagementException e) {
            throw new OperationManagementException("Error occurred while initiating a transaction", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        return updateStatus;
    }

    private boolean isTaskScheduledOperation(Operation operation) {
        DeviceManagementProviderService deviceManagementProviderService = DeviceManagementDataHolder.getInstance().
                getDeviceManagementProvider();
        List<MonitoringOperation> monitoringOperations = deviceManagementProviderService.getMonitoringOperationList(deviceType);//Get task list from each device type
        for (MonitoringOperation op : monitoringOperations) {
            if (operation.getCode().equals(op.getTaskName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSameUser(String user, String owner) {
        return user.equalsIgnoreCase(owner);
    }
}
