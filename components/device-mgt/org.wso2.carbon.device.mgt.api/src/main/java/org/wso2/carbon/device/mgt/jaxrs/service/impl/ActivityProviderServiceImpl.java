/*
 *   Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 *
 */
package org.wso2.carbon.device.mgt.jaxrs.service.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.device.mgt.common.DeviceIdentifier;
import org.wso2.carbon.device.mgt.common.operation.mgt.Activity;
import org.wso2.carbon.device.mgt.common.operation.mgt.OperationManagementException;
import org.wso2.carbon.device.mgt.core.service.DeviceManagementProviderService;
import org.wso2.carbon.device.mgt.jaxrs.beans.ActivityList;
import org.wso2.carbon.device.mgt.jaxrs.beans.ErrorResponse;
import org.wso2.carbon.device.mgt.jaxrs.common.ActivityIdList;
import org.wso2.carbon.device.mgt.jaxrs.service.api.ActivityInfoProviderService;
import org.wso2.carbon.device.mgt.jaxrs.service.impl.util.RequestValidationUtil;
import org.wso2.carbon.device.mgt.jaxrs.util.DeviceMgtAPIUtils;
import org.wso2.carbon.user.api.UserStoreException;

import javax.validation.constraints.Size;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@Path("/activities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ActivityProviderServiceImpl implements ActivityInfoProviderService {

    private static final Log log = LogFactory.getLog(ActivityProviderServiceImpl.class);

    @GET
    @Override
    @Path("/{id}")
    public Response getActivity(@PathParam("id")
                                @Size(max = 45) String id,
                                @HeaderParam("If-Modified-Since") String ifModifiedSince) {
        Activity activity;
        DeviceManagementProviderService dmService;
        Response response = validateAdminUser();
        if (response == null) {
            try {
                RequestValidationUtil.validateActivityId(id);

                dmService = DeviceMgtAPIUtils.getDeviceManagementService();
                activity = dmService.getOperationByActivityId(id);
                if (activity == null) {
                    return Response.status(404).entity(
                            new ErrorResponse.ErrorResponseBuilder().setMessage("No activity can be " +
                                    "found upon the provided activity id '" + id + "'").build()).build();
                }
                return Response.status(Response.Status.OK).entity(activity).build();
            } catch (OperationManagementException e) {
                String msg = "ErrorResponse occurred while fetching the activity for the supplied id.";
                log.error(msg, e);
                return Response.serverError().entity(
                        new ErrorResponse.ErrorResponseBuilder().setMessage(msg).build()).build();
            }
        } else {
            return response;
        }
    }

    @GET
    @Override
    @Path("/ids")
    public Response getActivities(@QueryParam("ids") ActivityIdList activityIdList) {

        List<String> idList;
        idList = activityIdList.getIdList();
        if (idList == null || idList.isEmpty()) {
            String msg = "Activity Ids shouldn't be empty";
            log.error(msg);
            return Response.status(400).entity(
                    new ErrorResponse.ErrorResponseBuilder().setMessage(msg).build()).build();
        }
        Response validationFailedResponse = validateAdminUser();
        if (validationFailedResponse == null) {
            List<Activity> activities;
            ActivityList activityList = new ActivityList();
            DeviceManagementProviderService dmService;
            try {
                for (String id : idList) {
                    RequestValidationUtil.validateActivityId(id);
                }
                dmService = DeviceMgtAPIUtils.getDeviceManagementService();
                activities = dmService.getOperationByActivityIds(idList);
                if (!activities.isEmpty()) {
                    activityList.setList(activities);
                    int count = activities.size();
                    if (log.isDebugEnabled()) {
                        log.debug("Number of activities : " + count);
                    }
                    activityList.setCount(count);
                    return Response.ok().entity(activityList).build();
                } else {
                    String msg = "No activity found with the given IDs.";
                    log.error(msg);
                    return Response.status(404).entity(
                            new ErrorResponse.ErrorResponseBuilder().setMessage(msg).build()).build();
                }
            } catch (OperationManagementException e) {
                String msg = "ErrorResponse occurred while fetching the activity list for the supplied ids.";
                log.error(msg, e);
                return Response.serverError().entity(
                        new ErrorResponse.ErrorResponseBuilder().setMessage(msg).build()).build();
            }
        } else {
            return validationFailedResponse;
        }
    }


    @GET
    @Override
    @Path("/{id}/{devicetype}/{deviceid}")
    public Response getActivityByDevice(@PathParam("id")
                                        @Size(max = 45) String id,
                                        @PathParam("devicetype")
                                        @Size(max = 45) String devicetype,
                                        @PathParam("deviceid")
                                        @Size(max = 45) String deviceid,
                                        @HeaderParam("If-Modified-Since") String ifModifiedSince) {
        Activity activity;
        DeviceManagementProviderService dmService;
        try {
            RequestValidationUtil.validateActivityId(id);

            DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
            deviceIdentifier.setId(deviceid);
            deviceIdentifier.setType(devicetype);

            dmService = DeviceMgtAPIUtils.getDeviceManagementService();
            activity = dmService.getOperationByActivityIdAndDevice(id, deviceIdentifier);
            if (activity == null) {
                return Response.status(404).entity(
                        new ErrorResponse.ErrorResponseBuilder().setMessage("No activity can be " +
                                "found upon the provided activity id '" + id + "'").build()).build();
            }
            return Response.status(Response.Status.OK).entity(activity).build();
        } catch (OperationManagementException e) {
            String msg = "ErrorResponse occurred while fetching the activity for the supplied id.";
            log.error(msg, e);
            return Response.serverError().entity(
                    new ErrorResponse.ErrorResponseBuilder().setMessage(msg).build()).build();
        }
    }

    @GET
    @Override
    @Path("/type/{operationCode}")
    public Response getActivities(@PathParam("operationCode") String operationCode,
                                  @QueryParam("offset") int offset, @QueryParam("limit") int limit){
        if (log.isDebugEnabled()) {
            log.debug("getActivities -> Operation Code : " +operationCode+ "offset " + offset + " limit: " + limit );
        }
        RequestValidationUtil.validatePaginationParameters(offset, limit);
        Response response = validateAdminUser();
        if(response == null){
            List<Activity> activities;
            ActivityList activityList = new ActivityList();
            DeviceManagementProviderService dmService;
            try {
                if (log.isDebugEnabled()) {
                    log.debug("Calling database to get activities for the operation code :" +operationCode);
                }
                dmService = DeviceMgtAPIUtils.getDeviceManagementService();
                activities = dmService.getFilteredActivities(operationCode, limit, offset);
                activityList.setList(activities);
                if (log.isDebugEnabled()) {
                    log.debug("Calling database to get activity count.");
                }
                int count = dmService.getTotalCountOfFilteredActivities(operationCode);
                if (log.isDebugEnabled()) {
                    log.debug("Activity count: " + count);
                }
                activityList.setCount(count);
                return Response.ok().entity(activityList).build();
            } catch (OperationManagementException e) {
                String msg
                        = "ErrorResponse occurred while fetching the activities for the given operation code.";
                log.error(msg, e);
                return Response.serverError().entity(
                        new ErrorResponse.ErrorResponseBuilder().setMessage(msg).build()).build();
            }
        } else {
            return response;
        }
    }


    @GET
    @Override
    public Response getActivities(@QueryParam("since") String since,  @QueryParam("initiatedBy")String initiatedBy,
                                  @QueryParam("offset") int offset, @QueryParam("limit") int limit,
                                  @HeaderParam("If-Modified-Since") String ifModifiedSince) {

        long ifModifiedSinceTimestamp;
        long sinceTimestamp;
        long timestamp = 0;
        boolean isIfModifiedSinceSet = false;
        if (log.isDebugEnabled()) {
            log.debug("getActivities since: " + since + " , offset: " + offset + " ,limit: " + limit + " ," +
                    "ifModifiedSince: " + ifModifiedSince);
        }
        RequestValidationUtil.validatePaginationParameters(offset, limit);
        if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
            Date ifSinceDate;
            SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z");
            try {
                ifSinceDate = format.parse(ifModifiedSince);
            } catch (ParseException e) {
                return Response.status(400).entity(
                        new ErrorResponse.ErrorResponseBuilder().setMessage(
                                "Invalid date string is provided in 'If-Modified-Since' header").build()).build();
            }
            ifModifiedSinceTimestamp = ifSinceDate.getTime();
            isIfModifiedSinceSet = true;
            timestamp = ifModifiedSinceTimestamp / 1000;
        } else if (since != null && !since.isEmpty()) {
            Date sinceDate;
            SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z");
            try {
                sinceDate = format.parse(since);
            } catch (ParseException e) {
                return Response.status(400).entity(
                        new ErrorResponse.ErrorResponseBuilder().setMessage(
                                "Invalid date string is provided in 'since' filter").build()).build();
            }
            sinceTimestamp = sinceDate.getTime();
            timestamp = sinceTimestamp / 1000;
        }

        if (timestamp == 0) {
            //If timestamp is not sent by the user, a default value is set, that is equal to current time-12 hours.
            long time = System.currentTimeMillis() / 1000;
            timestamp = time - 42300;
        }
        if (log.isDebugEnabled()) {
            log.debug("getActivities final timestamp " + timestamp);
        }
        Response response = validateAdminUser();
        if (response == null) {
            List<Activity> activities;
            int count = 0;
            ActivityList activityList = new ActivityList();
            DeviceManagementProviderService dmService;
            try {
                if (log.isDebugEnabled()) {
                    log.debug("Calling database to get activities.");
                }
                dmService = DeviceMgtAPIUtils.getDeviceManagementService();
                if (initiatedBy == null || initiatedBy.isEmpty()) {
                    activities = dmService.getActivitiesUpdatedAfter(timestamp, limit, offset);

                    if (log.isDebugEnabled()) {
                        log.debug("Calling database to get activity count with timestamp.");
                    }
                    count = dmService.getActivityCountUpdatedAfter(timestamp);
                    if (log.isDebugEnabled()) {
                        log.debug("Activity count: " + count);
                    }
                } else {
                    activities = dmService.getActivitiesUpdatedAfterByUser(timestamp, initiatedBy, limit, offset);

                    if (log.isDebugEnabled()) {
                        log.debug("Calling database to get activity count with timestamp and user.");
                    }
                    count = dmService.getActivityCountUpdatedAfterByUser(timestamp, initiatedBy);
                    if (log.isDebugEnabled()) {
                        log.debug("Activity count: " + count);
                    }
                }
                activityList.setList(activities);
                activityList.setCount(count);
                if (activities == null || activities.size() == 0) {
                    if (isIfModifiedSinceSet) {
                        return Response.notModified().build();
                    }
                }
                return Response.ok().entity(activityList).build();
            } catch (OperationManagementException e) {
                String msg
                        = "ErrorResponse occurred while fetching the activities updated after given time stamp.";
                log.error(msg, e);
                return Response.serverError().entity(
                        new ErrorResponse.ErrorResponseBuilder().setMessage(msg).build()).build();
            }
        } else {
            return response;
        }
    }

    private Response validateAdminUser(){
        try {
            if (!DeviceMgtAPIUtils.isAdmin()) {
                return Response.status(Response.Status.UNAUTHORIZED).entity("Unauthorized operation! Only admin role can perform " +
                        "this operation.").build();
            }
            return null;
        } catch (UserStoreException e) {
            String msg
                    = "Error occurred while validating the user have admin role!";
            log.error(msg, e);
            return Response.serverError().entity(
                    new ErrorResponse.ErrorResponseBuilder().setMessage(msg).build()).build();
        }
    }
}
