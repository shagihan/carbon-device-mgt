/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.device.mgt.jaxrs.api;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.wso2.carbon.device.mgt.common.device.details.DeviceInfo;
import org.wso2.carbon.device.mgt.common.device.details.DeviceLocation;
import org.wso2.carbon.device.mgt.jaxrs.beans.UserCredentialWrapper;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * Device information related operations.
 */
@Api(value = "DeviceInfo")
@SuppressWarnings("NonJaxWsWebServices")
public interface DeviceInformation {

    @GET
    @Path("{type}/{id}")
    @ApiOperation(
            produces = MediaType.APPLICATION_JSON,
            httpMethod = "GET",
            value = "Get device information",
            notes = "This will return device information such as CPU usage, memory usage etc.",
            response = DeviceInfo.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = ""),
            @ApiResponse(code = 400, message = ""),
            @ApiResponse(code = 400, message = ""),
            @ApiResponse(code = 500, message = "Internal Server Error")
            })
    Response getDeviceInfo(@ApiParam(name = "type", value = "Provide the device type, such as ios, android "
                                    + "or windows", required = true) @PathParam("type") String type,
                           @ApiParam(name = "id", value = "Provide the device identifier", required = true)
                                    @PathParam("id") String id);

    @GET
    @Path("location/{type}/{id}")
    @ApiOperation(
            produces = MediaType.APPLICATION_JSON,
            httpMethod = "GET",
            value = "Get the device location",
            notes = "This will return the device location including latitude and longitude as well the "
                    + "physical address",
            response = DeviceLocation.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = ""),
            @ApiResponse(code = 400, message = ""),
            @ApiResponse(code = 400, message = ""),
            @ApiResponse(code = 500, message = "Internal Server Error")
            })
    Response getDeviceLocation(@ApiParam(name = "type", value = "Provide the device type, such as ios, "
                                    + "android or windows", required = true) @PathParam("type") String type,
                               @ApiParam(name = "id", value = "Provide the device identifier",
                                       required = true) @PathParam("id") String id);

}
