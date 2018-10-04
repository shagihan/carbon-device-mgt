/*
 * Copyright (c) 2016a, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.device.mgt.core.operation.mgt.dao.impl.operation;

import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.device.mgt.common.DeviceIdentifier;
import org.wso2.carbon.device.mgt.common.operation.mgt.Activity;
import org.wso2.carbon.device.mgt.common.operation.mgt.ActivityStatus;
import org.wso2.carbon.device.mgt.common.operation.mgt.OperationResponse;
import org.wso2.carbon.device.mgt.core.dto.operation.mgt.Operation;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.OperationManagementDAOException;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.OperationManagementDAOFactory;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.OperationManagementDAOUtil;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.impl.GenericOperationDAOImpl;
import org.wso2.carbon.device.mgt.core.operation.mgt.dao.util.OperationDAOUtil;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * This class holds the implementation of OperationDAO which can be used to support MySQl db syntax.
 */
public class MySQLOperationDAOImpl extends GenericOperationDAOImpl {

    @Override
    public boolean updateOperationStatus(int enrolmentId, int operationId, Operation.Status status)
            throws OperationManagementDAOException {
        PreparedStatement stmt = null;
        boolean isUpdated = false;
        try {
            long time = System.currentTimeMillis() / 1000;
            Connection connection = OperationManagementDAOFactory.getConnection();
            stmt = connection.prepareStatement("SELECT STATUS, UPDATED_TIMESTAMP FROM DM_ENROLMENT_OP_MAPPING " +
                                               "WHERE ENROLMENT_ID=? and OPERATION_ID=? FOR UPDATE");
            stmt.setString(1, status.toString());
            stmt.setLong(2, time);
            if (stmt.execute()) {
                OperationManagementDAOUtil.cleanupResources(stmt);
                stmt = connection.prepareStatement("UPDATE DM_ENROLMENT_OP_MAPPING SET STATUS=?, UPDATED_TIMESTAMP=? " +
                                                   "WHERE ENROLMENT_ID=? and OPERATION_ID=?");
                stmt.setString(1, status.toString());
                stmt.setLong(2, time);
                stmt.setInt(3, enrolmentId);
                stmt.setInt(4, operationId);
                int numOfRecordsUpdated = stmt.executeUpdate();
                if (numOfRecordsUpdated != 0) {
                    isUpdated = true;
                }
            }
        } catch (SQLException e) {
            throw new OperationManagementDAOException("Error occurred while update device mapping operation status " +
                                                      "metadata", e);
        } finally {
            OperationManagementDAOUtil.cleanupResources(stmt);
        }
        return isUpdated;
    }

    @Override
    public List<Activity> getActivityList(List<Integer> activityIds) throws OperationManagementDAOException {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        Activity activity;
        List<Activity> activities = new ArrayList<>();

        try {
            Connection conn = OperationManagementDAOFactory.getConnection();

            String sql1 = "SELECT eom.ENROLMENT_ID, eom.OPERATION_ID, eom.ID AS EOM_MAPPING_ID, "
                    + "dor.ID AS OP_RES_ID, de.DEVICE_ID, d.DEVICE_IDENTIFICATION, d.DEVICE_TYPE_ID, "
                    + "dt.NAME AS DEVICE_TYPE_NAME, eom.STATUS, eom.CREATED_TIMESTAMP, "
                    + "eom.UPDATED_TIMESTAMP, op.OPERATION_CODE, op.TYPE AS OPERATION_TYPE, "
                    + "dor.OPERATION_RESPONSE, dor.RECEIVED_TIMESTAMP, op.INITIATED_BY FROM "
                    + "DM_ENROLMENT_OP_MAPPING eom INNER JOIN DM_OPERATION op "
                    + "ON op.ID=eom.OPERATION_ID INNER JOIN DM_ENROLMENT de "
                    + "ON de.ID=eom.ENROLMENT_ID INNER JOIN DM_DEVICE d ON d.ID=de.DEVICE_ID \n"
                    + "INNER JOIN DM_DEVICE_TYPE dt ON dt.ID=d.DEVICE_TYPE_ID\n"
                    + "LEFT JOIN DM_DEVICE_OPERATION_RESPONSE dor ON dor.ENROLMENT_ID=de.id \n"
                    + "AND dor.OPERATION_ID = eom.OPERATION_ID WHERE eom.OPERATION_ID " + "IN (";

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < activityIds.size(); i++) {
                builder.append("?,");
            }
            sql1 += builder.deleteCharAt(builder.length() - 1).toString() + ") AND de.TENANT_ID = ?";
            stmt = conn.prepareStatement(sql1);
            int i;
            for (i = 0; i < activityIds.size(); i++) {
                stmt.setInt(i + 1, activityIds.get(i));
            }
            stmt.setInt(i + 1, PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId());

            rs = stmt.executeQuery();

            int operationId = 0;
            int enrolmentId = 0;
            int responseId = 0;
            ActivityStatus activityStatus = new ActivityStatus();
            while (rs.next()) {
                activity = new Activity();

                if (operationId != rs.getInt("OPERATION_ID")) {
                    activities.add(activity);
                    List<ActivityStatus> statusList = new ArrayList<>();
                    activityStatus = new ActivityStatus();

                    operationId = rs.getInt("OPERATION_ID");
                    enrolmentId = rs.getInt("ENROLMENT_ID");

                    activity.setType(Activity.Type.valueOf(rs.getString("OPERATION_TYPE")));
                    activity.setCreatedTimeStamp(
                            new java.util.Date(rs.getLong(("CREATED_TIMESTAMP")) * 1000).toString());
                    activity.setCode(rs.getString("OPERATION_CODE"));
                    activity.setInitiatedBy(rs.getString("INITIATED_BY"));

                    DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
                    deviceIdentifier.setId(rs.getString("DEVICE_IDENTIFICATION"));
                    deviceIdentifier.setType(rs.getString("DEVICE_TYPE_NAME"));

                    activityStatus.setDeviceIdentifier(deviceIdentifier);

                    activityStatus.setStatus(ActivityStatus.Status.valueOf(rs.getString("STATUS")));

                    List<OperationResponse> operationResponses = new ArrayList<>();
                    if (rs.getInt("UPDATED_TIMESTAMP") != 0) {
                        activityStatus.setUpdatedTimestamp(
                                new java.util.Date(rs.getLong(("UPDATED_TIMESTAMP")) * 1000).toString());

                    }
                    if (rs.getTimestamp("RECEIVED_TIMESTAMP") != null) {
                        operationResponses.add(OperationDAOUtil.getOperationResponse(rs));
                        responseId = rs.getInt("OP_RES_ID");
                    }
                    activityStatus.setResponses(operationResponses);
                    statusList.add(activityStatus);
                    activity.setActivityStatus(statusList);
                    activity.setActivityId(OperationDAOUtil.getActivityId(rs.getInt("OPERATION_ID")));
                }

                if (operationId == rs.getInt("OPERATION_ID") && enrolmentId != rs.getInt("ENROLMENT_ID")) {
                    activityStatus = new ActivityStatus();

                    activity.setType(Activity.Type.valueOf(rs.getString("OPERATION_TYPE")));
                    activity.setCreatedTimeStamp(
                            new java.util.Date(rs.getLong(("CREATED_TIMESTAMP")) * 1000).toString());
                    activity.setCode(rs.getString("OPERATION_CODE"));
                    activity.setInitiatedBy(rs.getString("INITIATED_BY"));

                    DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
                    deviceIdentifier.setId(rs.getString("DEVICE_IDENTIFICATION"));
                    deviceIdentifier.setType(rs.getString("DEVICE_TYPE_NAME"));
                    activityStatus.setDeviceIdentifier(deviceIdentifier);

                    activityStatus.setStatus(ActivityStatus.Status.valueOf(rs.getString("STATUS")));

                    List<OperationResponse> operationResponses = new ArrayList<>();
                    if (rs.getInt("UPDATED_TIMESTAMP") != 0) {
                        activityStatus.setUpdatedTimestamp(
                                new java.util.Date(rs.getLong(("UPDATED_TIMESTAMP")) * 1000).toString());
                    }
                    if (rs.getTimestamp("RECEIVED_TIMESTAMP") != null) {
                        operationResponses.add(OperationDAOUtil.getOperationResponse(rs));
                        responseId = rs.getInt("OP_RES_ID");
                    }
                    activityStatus.setResponses(operationResponses);
                    activity.getActivityStatus().add(activityStatus);

                    enrolmentId = rs.getInt("ENROLMENT_ID");
                }

                if (rs.getInt("OP_RES_ID") != 0 && responseId != rs.getInt("OP_RES_ID") && rs.getTimestamp(
                        "RECEIVED_TIMESTAMP") != null) {
                    activityStatus.getResponses().add(OperationDAOUtil.getOperationResponse(rs));
                    responseId = rs.getInt("OP_RES_ID");
                }
            }

        } catch (SQLException e) {
            throw new OperationManagementDAOException(
                    "Error occurred while getting the operation details from " + "the database.", e);
        } catch (ClassNotFoundException e) {
            throw new OperationManagementDAOException(
                    "Error occurred while converting the operation response to string.", e);
        } catch (IOException e) {
            throw new OperationManagementDAOException(
                    "IO exception occurred while converting the operations responses.", e);
        } finally {
            OperationManagementDAOUtil.cleanupResources(stmt, rs);
        }
        return activities;
    }



    @Override
    public List<Activity> getActivitiesUpdatedAfter(long timestamp, int limit,
                                                    int offset) throws OperationManagementDAOException {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        List<Activity> activities = new ArrayList<>();
        try {
            Connection conn = OperationManagementDAOFactory.getConnection();

            int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
            String sql = "SELECT " +
                    "    opr.ENROLMENT_ID, " +
                    "    opr.CREATED_TIMESTAMP, " +
                    "    opr.UPDATED_TIMESTAMP, " +
                    "    opr.OPERATION_ID, " +
                    "    opr.OPERATION_CODE, " +
                    "    opr.INITIATED_BY, " +
                    "    opr.OPERATION_TYPE, " +
                    "    opr.STATUS, " +
                    "    opr.DEVICE_ID, " +
                    "    opr.DEVICE_IDENTIFICATION, " +
                    "    opr.DEVICE_TYPE, " +
                    "    ops.RECEIVED_TIMESTAMP, " +
                    "    ops.ID OP_RES_ID, " +
                    "    ops.OPERATION_RESPONSE " +
                    " FROM " +
                    "    (SELECT " +
                    "            opm.ID MAPPING_ID, " +
                    "            opm.ENROLMENT_ID, " +
                    "            opm.CREATED_TIMESTAMP, " +
                    "            opm.UPDATED_TIMESTAMP, " +
                    "            opm.OPERATION_ID, " +
                    "            op.OPERATION_CODE, " +
                    "            op.INITIATED_BY, " +
                    "            op.TYPE  OPERATION_TYPE, " +
                    "            opm.STATUS, " +
                    "            en.DEVICE_ID, " +
                    "            de.DEVICE_IDENTIFICATION, " +
                    "            dt.NAME  DEVICE_TYPE, " +
                    "            de.TENANT_ID " +
                    "    FROM" +
                    "        DM_ENROLMENT_OP_MAPPING  opm FORCE INDEX (IDX_ENROLMENT_OP_MAPPING) " +
                    "        INNER JOIN DM_OPERATION  op ON opm.OPERATION_ID = op.ID " +
                    "        INNER JOIN DM_ENROLMENT  en ON opm.ENROLMENT_ID = en.ID " +
                    "        INNER JOIN DM_DEVICE  de ON en.DEVICE_ID = de.ID " +
                    "        INNER JOIN DM_DEVICE_TYPE  dt ON dt.ID = de.DEVICE_TYPE_ID " +
                    "    WHERE" +
                    "        opm.UPDATED_TIMESTAMP > ? " +
                    "            AND de.TENANT_ID = ? " +
                    "    ORDER BY opm.UPDATED_TIMESTAMP " +
                    "    LIMIT ? OFFSET ?) opr " +
                    " LEFT JOIN DM_DEVICE_OPERATION_RESPONSE ops ON opr.MAPPING_ID = ops.EN_OP_MAP_ID " +
                    " WHERE " +
                    "    opr.UPDATED_TIMESTAMP > ? " +
                    "    AND opr.TENANT_ID = ? ";

            stmt = conn.prepareStatement(sql);

            stmt.setLong(1, timestamp);
            stmt.setInt(2, tenantId);
            stmt.setInt(3, limit);
            stmt.setInt(4, offset);
            stmt.setLong(5, timestamp);
            stmt.setInt(6, tenantId);

            rs = stmt.executeQuery();

            int operationId = 0;
            int enrolmentId = 0;
            int responseId = 0;
            Activity activity = null;
            ActivityStatus activityStatus = null;
            while (rs.next()) {

                if (operationId != rs.getInt("OPERATION_ID")) {
                    activity = new Activity();
                    activities.add(activity);
                    List<ActivityStatus> statusList = new ArrayList<>();
                    activityStatus = new ActivityStatus();

                    operationId = rs.getInt("OPERATION_ID");
                    enrolmentId = rs.getInt("ENROLMENT_ID");

                    activity.setType(Activity.Type.valueOf(rs.getString("OPERATION_TYPE")));
                    activity.setCreatedTimeStamp(new java.util.Date(rs.getLong(("CREATED_TIMESTAMP")) * 1000).toString());
                    activity.setCode(rs.getString("OPERATION_CODE"));
                    activity.setInitiatedBy(rs.getString("INITIATED_BY"));

                    DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
                    deviceIdentifier.setId(rs.getString("DEVICE_IDENTIFICATION"));
                    deviceIdentifier.setType(rs.getString("DEVICE_TYPE"));
                    activityStatus.setDeviceIdentifier(deviceIdentifier);

                    activityStatus.setStatus(ActivityStatus.Status.valueOf(rs.getString("STATUS")));

                    List<OperationResponse> operationResponses = new ArrayList<>();
                    if (rs.getInt("UPDATED_TIMESTAMP") != 0) {
                        activityStatus.setUpdatedTimestamp(new java.util.Date(
                                rs.getLong(("UPDATED_TIMESTAMP")) * 1000).toString());

                    }
                    if (rs.getTimestamp("RECEIVED_TIMESTAMP") != null) {
                        operationResponses.add(OperationDAOUtil.getOperationResponse(rs));
                        responseId = rs.getInt("OP_RES_ID");
                    }
                    activityStatus.setResponses(operationResponses);
                    statusList.add(activityStatus);
                    activity.setActivityStatus(statusList);
                    activity.setActivityId(OperationDAOUtil.getActivityId(rs.getInt("OPERATION_ID")));

                }

                if (operationId == rs.getInt("OPERATION_ID") && enrolmentId != rs.getInt("ENROLMENT_ID")) {
                    activityStatus = new ActivityStatus();

                    activity.setType(Activity.Type.valueOf(rs.getString("OPERATION_TYPE")));
                    activity.setCreatedTimeStamp(new java.util.Date(rs.getLong(("CREATED_TIMESTAMP")) * 1000).toString());
                    activity.setCode(rs.getString("OPERATION_CODE"));
                    activity.setInitiatedBy(rs.getString("INITIATED_BY"));

                    DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
                    deviceIdentifier.setId(rs.getString("DEVICE_IDENTIFICATION"));
                    deviceIdentifier.setType(rs.getString("DEVICE_TYPE"));
                    activityStatus.setDeviceIdentifier(deviceIdentifier);

                    activityStatus.setStatus(ActivityStatus.Status.valueOf(rs.getString("STATUS")));

                    List<OperationResponse> operationResponses = new ArrayList<>();
                    if (rs.getInt("UPDATED_TIMESTAMP") != 0) {
                        activityStatus.setUpdatedTimestamp(new java.util.Date(
                                rs.getLong(("UPDATED_TIMESTAMP")) * 1000).toString());
                    }
                    if (rs.getTimestamp("RECEIVED_TIMESTAMP") != null) {
                        operationResponses.add(OperationDAOUtil.getOperationResponse(rs));
                        responseId = rs.getInt("OP_RES_ID");
                    }
                    activityStatus.setResponses(operationResponses);
                    activity.getActivityStatus().add(activityStatus);

                    enrolmentId = rs.getInt("ENROLMENT_ID");
                }

                if (rs.getInt("OP_RES_ID") != 0 && responseId != rs.getInt("OP_RES_ID")) {
                    if (rs.getTimestamp("RECEIVED_TIMESTAMP") != null) {
                        activityStatus.getResponses().add(OperationDAOUtil.getOperationResponse(rs));
                        responseId = rs.getInt("OP_RES_ID");
                    }
                }
            }
        } catch (SQLException e) {
            throw new OperationManagementDAOException("Error occurred while getting the operation details from " +
                    "the database.", e);
        } catch (ClassNotFoundException e) {
            throw new OperationManagementDAOException("Error occurred while converting the operation response to string.", e);
        } catch (IOException e) {
            throw new OperationManagementDAOException("IO exception occurred while converting the operations responses.", e);
        } finally {
            OperationManagementDAOUtil.cleanupResources(stmt, rs);
        }
        return activities;
    }

    @Override
    public List<Activity> getActivitiesUpdatedAfterByUser(long timestamp, String user, int limit, int offset)
            throws OperationManagementDAOException {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        List<Activity> activities = new ArrayList<>();
        try {
            Connection conn = OperationManagementDAOFactory.getConnection();

            int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
            String sql = "SELECT " +
                    "    opr.ENROLMENT_ID, " +
                    "    opr.CREATED_TIMESTAMP, " +
                    "    opr.UPDATED_TIMESTAMP, " +
                    "    opr.OPERATION_ID, " +
                    "    opr.OPERATION_CODE, " +
                    "    opr.INITIATED_BY, " +
                    "    opr.OPERATION_TYPE, " +
                    "    opr.STATUS, " +
                    "    opr.DEVICE_ID, " +
                    "    opr.DEVICE_IDENTIFICATION, " +
                    "    opr.DEVICE_TYPE, " +
                    "    ops.RECEIVED_TIMESTAMP, " +
                    "    ops.ID OP_RES_ID, " +
                    "    ops.OPERATION_RESPONSE " +
                    " FROM " +
                    "    (SELECT " +
                    "            opm.ID MAPPING_ID, " +
                    "            opm.ENROLMENT_ID, " +
                    "            opm.CREATED_TIMESTAMP, " +
                    "            opm.UPDATED_TIMESTAMP, " +
                    "            opm.OPERATION_ID, " +
                    "            op.OPERATION_CODE, " +
                    "            op.INITIATED_BY, " +
                    "            op.TYPE  OPERATION_TYPE, " +
                    "            opm.STATUS, " +
                    "            en.DEVICE_ID, " +
                    "            de.DEVICE_IDENTIFICATION, " +
                    "            dt.NAME  DEVICE_TYPE, " +
                    "            de.TENANT_ID " +
                    "    FROM" +
                    "        DM_ENROLMENT_OP_MAPPING  opm FORCE INDEX (IDX_ENROLMENT_OP_MAPPING) " +
                    "        INNER JOIN DM_OPERATION  op ON opm.OPERATION_ID = op.ID " +
                    "        INNER JOIN DM_ENROLMENT  en ON opm.ENROLMENT_ID = en.ID " +
                    "        INNER JOIN DM_DEVICE  de ON en.DEVICE_ID = de.ID " +
                    "        INNER JOIN DM_DEVICE_TYPE  dt ON dt.ID = de.DEVICE_TYPE_ID " +
                    "    WHERE" +
                    "        opm.UPDATED_TIMESTAMP > ? AND op.INITIATED_BY = ?" +
                    "            AND de.TENANT_ID = ? " +
                    "    ORDER BY opm.UPDATED_TIMESTAMP " +
                    "    LIMIT ? OFFSET ?) opr " +
                    " LEFT JOIN DM_DEVICE_OPERATION_RESPONSE ops ON opr.MAPPING_ID = ops.EN_OP_MAP_ID " +
                    " WHERE " +
                    "    opr.UPDATED_TIMESTAMP > ? AND opr.INITIATED_BY = ? " +
                    "    AND opr.TENANT_ID = ? ";

            stmt = conn.prepareStatement(sql);

            stmt.setLong(1, timestamp);
            stmt.setString(2, user);
            stmt.setInt(3, tenantId);
            stmt.setInt(4, limit);
            stmt.setInt(5, offset);
            stmt.setLong(6, timestamp);
            stmt.setString(7, user);
            stmt.setInt(8, tenantId);

            rs = stmt.executeQuery();

            int operationId = 0;
            int enrolmentId = 0;
            int responseId = 0;
            Activity activity = null;
            ActivityStatus activityStatus = null;
            while (rs.next()) {

                if (operationId != rs.getInt("OPERATION_ID")) {
                    activity = new Activity();
                    activities.add(activity);
                    List<ActivityStatus> statusList = new ArrayList<>();
                    activityStatus = new ActivityStatus();

                    operationId = rs.getInt("OPERATION_ID");
                    enrolmentId = rs.getInt("ENROLMENT_ID");

                    activity.setType(Activity.Type.valueOf(rs.getString("OPERATION_TYPE")));
                    activity.setCreatedTimeStamp(new java.util.Date(rs.getLong(("CREATED_TIMESTAMP")) * 1000).toString());
                    activity.setCode(rs.getString("OPERATION_CODE"));
                    activity.setInitiatedBy(rs.getString("INITIATED_BY"));

                    DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
                    deviceIdentifier.setId(rs.getString("DEVICE_IDENTIFICATION"));
                    deviceIdentifier.setType(rs.getString("DEVICE_TYPE"));
                    activityStatus.setDeviceIdentifier(deviceIdentifier);

                    activityStatus.setStatus(ActivityStatus.Status.valueOf(rs.getString("STATUS")));

                    List<OperationResponse> operationResponses = new ArrayList<>();
                    if (rs.getInt("UPDATED_TIMESTAMP") != 0) {
                        activityStatus.setUpdatedTimestamp(new java.util.Date(
                                rs.getLong(("UPDATED_TIMESTAMP")) * 1000).toString());

                    }
                    if (rs.getTimestamp("RECEIVED_TIMESTAMP") != (null)) {
                        operationResponses.add(OperationDAOUtil.getOperationResponse(rs));
                        responseId = rs.getInt("OP_RES_ID");
                    }
                    activityStatus.setResponses(operationResponses);
                    statusList.add(activityStatus);
                    activity.setActivityStatus(statusList);
                    activity.setActivityId(OperationDAOUtil.getActivityId(rs.getInt("OPERATION_ID")));

                }

                if (operationId == rs.getInt("OPERATION_ID") && enrolmentId != rs.getInt("ENROLMENT_ID")) {
                    activityStatus = new ActivityStatus();

                    activity.setType(Activity.Type.valueOf(rs.getString("OPERATION_TYPE")));
                    activity.setCreatedTimeStamp(new java.util.Date(rs.getLong(("CREATED_TIMESTAMP")) * 1000).toString());
                    activity.setCode(rs.getString("OPERATION_CODE"));
                    activity.setInitiatedBy(rs.getString("INITIATED_BY"));

                    DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
                    deviceIdentifier.setId(rs.getString("DEVICE_IDENTIFICATION"));
                    deviceIdentifier.setType(rs.getString("DEVICE_TYPE"));
                    activityStatus.setDeviceIdentifier(deviceIdentifier);

                    activityStatus.setStatus(ActivityStatus.Status.valueOf(rs.getString("STATUS")));

                    List<OperationResponse> operationResponses = new ArrayList<>();
                    if (rs.getInt("UPDATED_TIMESTAMP") != 0) {
                        activityStatus.setUpdatedTimestamp(new java.util.Date(
                                rs.getLong(("UPDATED_TIMESTAMP")) * 1000).toString());
                    }
                    if (rs.getTimestamp("RECEIVED_TIMESTAMP") != (null)) {
                        operationResponses.add(OperationDAOUtil.getOperationResponse(rs));
                        responseId = rs.getInt("OP_RES_ID");
                    }
                    activityStatus.setResponses(operationResponses);
                    activity.getActivityStatus().add(activityStatus);

                    enrolmentId = rs.getInt("ENROLMENT_ID");
                }

                if (rs.getInt("OP_RES_ID") != 0 && responseId != rs.getInt("OP_RES_ID")) {
                    if (rs.getTimestamp("RECEIVED_TIMESTAMP") != (null)) {
                        activityStatus.getResponses().add(OperationDAOUtil.getOperationResponse(rs));
                        responseId = rs.getInt("OP_RES_ID");
                    }
                }
            }
        } catch (SQLException e) {
            throw new OperationManagementDAOException("Error occurred while getting the operation details from " +
                    "the database.", e);
        } catch (ClassNotFoundException e) {
            throw new OperationManagementDAOException("Error occurred while converting the operation response to string.", e);
        } catch (IOException e) {
            throw new OperationManagementDAOException("IO exception occurred while converting the operations responses.", e);
        } finally {
            OperationManagementDAOUtil.cleanupResources(stmt, rs);
        }
        return activities;
    }

    private Integer[] getIntArrayOfActivityIds(List<Integer> activityIds) {
        Integer[] arr = new Integer[activityIds.size()];
        int x = 0;
        for (Integer activityId : activityIds) {
            arr[x] = activityId;
            x++;
        }
        return arr;
    }
}