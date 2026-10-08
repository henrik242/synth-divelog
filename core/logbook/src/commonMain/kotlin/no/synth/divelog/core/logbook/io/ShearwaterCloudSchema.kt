package no.synth.divelog.core.logbook.io

/**
 * The tables of a Shearwater Cloud database export, as Shearwater Cloud writes them, and
 * the schema version rows it keeps. An export we write uses them unchanged so Shearwater
 * Cloud opens it like one of its own.
 */
internal object ShearwaterCloudSchema {
    val TABLES: List<String> = listOf(
        "CREATE TABLE \"dive_details\"(\n\"DiveId\" varchar primary key not null ,\n" +
            "\"DataVersion\" bigint ,\n\"FileName\" varchar ,\n\"LastModified\" datetime ,\n" +
            "\"MainTimestampReliable\" varchar ,\n\"DiveDate\" datetime ,\n\"Depth\" varchar ,\n" +
            "\"SerialNumber\" varchar ,\n\"AverageDepth\" float ,\n\"AverageTemp\" float ,\n\"MinTemp\" float ,\n" +
            "\"MaxTemp\" float ,\n\"EndGF99\" float ,\n\"DiveLengthTime\" varchar ,\n\"Location\" varchar ,\n" +
            "\"Site\" varchar ,\n\"Buddy\" varchar ,\n\"DateAndTime\" varchar ,\n\"DiveNumber\" varchar ,\n" +
            "\"Environment\" varchar ,\n\"EnvironmentNotes\" varchar ,\n\"Visibility\" varchar ,\n" +
            "\"Weather\" varchar ,\n\"Conditions\" varchar ,\n\"Platform\" varchar ,\n" +
            "\"AirTemperature\" varchar ,\n\"GnssEntryLocation\" varchar ,\n\"GnssExitLocation\" varchar ,\n" +
            "\"GnssCustomWaypoints\" varchar ,\n\"TankProfileData\" varchar ,\n" +
            "\"Tank1PressureStart\" varchar ,\n\"Tank1PressureEnd\" varchar ,\n" +
            "\"Tank2PressureStart\" varchar ,\n\"Tank2PressureEnd\" varchar ,\n" +
            "\"Tank3PressureStart\" varchar ,\n\"Tank3PressureEnd\" varchar ,\n" +
            "\"Tank4PressureStart\" varchar ,\n\"Tank4PressureEnd\" varchar ,\n\"AverageSAC\" varchar ,\n" +
            "\"TankSize\" varchar ,\n\"GasNotes\" varchar ,\n\"Weight\" varchar ,\n\"GearNotes\" varchar ,\n" +
            "\"Dress\" varchar ,\n\"Apparatus\" varchar ,\n\"ThermalComfort\" varchar ,\n\"Workload\" varchar ,\n" +
            "\"Problems\" varchar ,\n\"Malfunctions\" varchar ,\n\"Symptoms\" varchar ,\n" +
            "\"ExposureToAltitude\" varchar ,\n\"Notes\" varchar ,\n\"Other1\" varchar ,\n\"Other2\" varchar ,\n" +
            "\"Other3\" varchar ,\n\"IssueNotes\" varchar ,\n\"AveloNotes\" varchar )",
        "CREATE TABLE \"dive_log_records\"(\n\"id\" integer primary key autoincrement not null ,\n" +
            "\"diveLogId\" varchar ,\n\"currentTime\" integer ,\n\"currentDepth\" float ,\n" +
            "\"firstStopDepth\" integer ,\n\"ttsMins\" integer ,\n\"averagePPO2\" float ,\n\"fractionO2\" float ,\n" +
            "\"fractionHe\" float ,\n\"firstStopTime\" integer ,\n\"currentNdl\" integer ,\n" +
            "\"systemByte\" integer ,\n\"currentCircuitSetting\" integer ,\n\"currentCcrMode\" integer ,\n" +
            "\"waterTemp\" integer ,\n\"errorAcks\" integer ,\n\"errorFlags\" integer ,\n" +
            "\"gasSwitchNeeded\" integer ,\n\"externalPPO2\" integer ,\n\"setPointType\" integer ,\n" +
            "\"circuitSwitchType\" integer ,\n\"sensor1Millivolts\" integer ,\n\"sensor2Millivolts\" integer ,\n" +
            "\"sensor3Millivolts\" integer ,\n\"batteryVoltage\" float ,\n\"backlightCurrent\" integer ,\n" +
            "\"currentPPO2SetPoint\" integer ,\n\"lightLevelReading\" integer ,\n\"CNSPercent\" integer ,\n" +
            "\"decoCeiling\" integer ,\n\"gf99\" integer ,\n\"safeAscentDepth\" float ,\n\"atPlusFive\" integer ,\n" +
            "\"aiSensor0_BatteryLevel\" integer ,\n\"aiSensor0_PressurePSI\" integer ,\n" +
            "\"aiSensor1_BatteryLevel\" integer ,\n\"aiSensor1_PressurePSI\" integer ,\n" +
            "\"aiSensor2_BatteryLevel\" integer ,\n\"aiSensor2_PressurePSI\" integer ,\n" +
            "\"aiSensor3_BatteryLevel\" integer ,\n\"aiSensor3_PressurePSI\" integer ,\n\"gasTime\" integer ,\n" +
            "\"RespiratoryMinuteVolume\" float ,\n\"ascentRate\" float ,\n" +
            "\"BatteryPercentRemaining\" integer ,\n\"HpDilPressure\" integer ,\n\"HpO2Pressure\" integer ,\n" +
            "\"RawBytes\" blob )",
        "CREATE TABLE \"dive_logs\"(\n\"id\" integer ,\n\"diveId\" varchar primary key not null ,\n" +
            "\"PrimaryKey\" varchar ,\n\"DiveAutoInc\" integer ,\n\"number\" integer ,\n\"gfMin\" integer ,\n" +
            "\"gfMax\" integer ,\n\"surfaceMins\" integer ,\n\"imperialUnits\" integer ,\n" +
            "\"startBatteryVoltage\" float ,\n\"startCNS\" integer ,\n\"startDate\" varchar ,\n" +
            "\"startO2SensorStatus\" integer ,\n\"startLowSetPoint\" integer ,\n" +
            "\"startHighSetPoint\" integer ,\n\"computerFirmware\" integer ,\n\"maxDepth\" integer ,\n" +
            "\"maxDepthFloat\" float ,\n\"maxTime\" integer ,\n\"errorHistory\" integer ,\n" +
            "\"endBatteryVoltage\" float ,\n\"endCNS\" integer ,\n\"endDate\" varchar ,\n" +
            "\"endO2SensorStatus\" integer ,\n\"endLowSetPoint\" integer ,\n\"endHighSetPoint\" integer ,\n" +
            "\"computerSerial\" integer ,\n\"computerSoftwareVersion\" integer ,\n\"computerModel\" integer ,\n" +
            "\"logVersion\" integer ,\n\"dbVersion\" integer ,\n\"startTimestamp\" integer ,\n" +
            "\"endTimeStamp\" integer ,\n\"decoModel\" integer ,\n\"vpmbConservatism\" integer ,\n" +
            "\"startSurfacePressure\" integer ,\n\"endSurfacePressure\" integer ,\n\"sensorDisplay\" integer ,\n" +
            "\"product\" integer ,\n\"features\" integer ,\n\"startGFS\" integer ,\n" +
            "\"startsensor1Calibrated\" integer ,\n\"startSensor2Calibrated\" integer ,\n" +
            "\"startSensor3Calibrated\" integer ,\n\"startsensor1CalibrationValue\" integer ,\n" +
            "\"startsensor2CalibrationValue\" integer ,\n\"startsensor3CalibrationValue\" integer ,\n" +
            "\"startsensor1ADCOffset\" float ,\n\"startsensor2ADCOffset\" float ,\n" +
            "\"startsensor3ADCOffset\" float ,\n\"startO2DecoMode\" integer ,\n\"startO2TempGender\" integer ,\n" +
            "\"startO2TempWeight\" integer ,\n\"startBatteryGuageAvailable\" integer ,\n" +
            "\"startBatteryPercentage\" integer ,\n\"startBatteryType\" integer ,\n" +
            "\"startBatterySetting\" integer ,\n\"startBatteryWarningLevel\" integer ,\n" +
            "\"startBatteryCriticalLevel\" integer ,\n\"startHSI_Trim_Location\" integer ,\n" +
            "\"startLogVersion\" integer ,\n\"endsensor1Calibrated\" integer ,\n" +
            "\"endsensor2Calibrated\" integer ,\n\"endsensor3Calibrated\" integer ,\n" +
            "\"endsensor1CalibrationValue\" integer ,\n\"endsensor2CalibrationValue\" integer ,\n" +
            "\"endsensor3CalibrationValue\" integer ,\n\"endsensor1ADCOffset\" float ,\n" +
            "\"endsensor2ADCOffset\" float ,\n\"endsensor3ADCOffset\" float ,\n\"endO2DecoMode\" integer ,\n" +
            "\"endO2TempGender\" integer ,\n\"endO2TempWeight\" integer ,\n" +
            "\"endBatteryGuageAvailable\" integer ,\n\"endBatteryPercentage\" integer ,\n" +
            "\"endBatteryType\" integer ,\n\"endBatterySetting\" integer ,\n" +
            "\"endBatteryWarningLevel\" integer ,\n\"endBatteryCriticalLevel\" integer ,\n" +
            "\"endHSI_Trim_Location\" integer ,\n\"endLogVersion\" integer ,\n" +
            "\"maxDescentRateMbarPerMinute\" float ,\n\"maxAscentRateMbarPerMinute\" float ,\n" +
            "\"avgDescentRateMbarPerMinute\" float ,\n\"avgAscentRateMbarPerMinute\" float ,\n" +
            "\"startComputerSerialNumber\" bigint ,\n\"timeZoneOffsetInMinutes\" integer ,\n" +
            "\"daylightSavings\" integer ,\n\"HeaderSurfaceTime\" integer ,\n" +
            "\"HeaderO2Sensor1Status\" integer ,\n\"HeaderO2Sensor2Status\" integer ,\n" +
            "\"HeaderO2Sensor3Status\" integer ,\n\"HeaderOcGas0O2Percent\" integer ,\n" +
            "\"HeaderOcGas1O2Percent\" integer ,\n\"HeaderOcGas2O2Percent\" integer ,\n" +
            "\"HeaderOcGas3O2Percent\" integer ,\n\"HeaderOcGas4O2Percent\" integer ,\n" +
            "\"HeaderOcGas0HePercent\" integer ,\n\"HeaderOcGas1HePercent\" integer ,\n" +
            "\"HeaderOcGas2HePercent\" integer ,\n\"HeaderOcGas3HePercent\" integer ,\n" +
            "\"HeaderOcGas4HePercent\" integer ,\n\"HeaderCcGas0O2Percent\" integer ,\n" +
            "\"HeaderCcGas1O2Percent\" integer ,\n\"HeaderCcGas2O2Percent\" integer ,\n" +
            "\"HeaderCcGas3O2Percent\" integer ,\n\"HeaderCcGas4O2Percent\" integer ,\n" +
            "\"HeaderCcGas0HePercent\" integer ,\n\"HeaderCcGas1HePercent\" integer ,\n" +
            "\"HeaderCcGas2HePercent\" integer ,\n\"HeaderCcGas3HePercent\" integer ,\n" +
            "\"HeaderCcGas4HePercent\" integer ,\n\"HeaderSwitchUpSetting\" integer ,\n" +
            "\"HeaderSwitchUpDepth\" integer ,\n\"HeaderSwitchDownSetting\" integer ,\n" +
            "\"HeaderSwitchDownDepth\" integer ,\n\"HeaderO2SensorMode\" integer ,\n" +
            "\"HeaderErrorFlags0\" varchar ,\n\"HeaderErrorFlags1\" varchar ,\n\"HeaderErrorFlags2\" varchar ,\n" +
            "\"HeaderErrorFlags3\" varchar ,\n\"HeaderErrorAcks0\" varchar ,\n\"HeaderErrorAcks1\" varchar ,\n" +
            "\"HeaderErrorAcks2\" varchar ,\n\"HeaderErrorAcks3\" varchar ,\n" +
            "\"HeaderCurrentEventLogNumber\" integer ,\n\"HeaderSolenoidDepthCompensation\" integer ,\n" +
            "\"HeaderOcMinimumPPO2\" float ,\n\"HeaderOcMaximumPPO2\" float ,\n\"HeaderOcDecoPPO2\" float ,\n" +
            "\"HeaderCcMinimumPPO2\" float ,\n\"HeaderCcMaximumPPO2\" float ,\n" +
            "\"HeaderDecoViolatedDisplayed\" integer ,\n\"HeaderLastStopDepth\" integer ,\n" +
            "\"HeaderEndDiveDelay\" integer ,\n\"HeaderClockFormat\" integer ,\n\"HeaderTitleColor\" integer ,\n" +
            "\"HeaderShowOCOnlyPPO2\" integer ,\n\"HeaderSalinity\" integer ,\n" +
            "\"HeaderCo2TempEnabled\" integer ,\n\"HeaderCo2TempWarmupFlags\" integer ,\n" +
            "\"HeaderCo2TempReadyFlags\" integer ,\n\"HeaderCo2TempScrubberState\" integer ,\n" +
            "\"HeaderCo2TempCurrentRct\" integer ,\n\"HeaderCo2TempCurrentRst\" integer ,\n" +
            "\"HeaderCo2TempMinimumRct\" integer ,\n\"HeaderCo2TempMinimumRctDiveMinutes\" integer ,\n" +
            "\"HeaderCo2TempMinimumRst\" integer ,\n\"HeaderCo2TempMinimumRstDiveMinutes\" integer ,\n" +
            "\"HeaderMode\" integer ,\n\"HeaderGasState\" integer ,\n\"HeaderAI_Mode\" integer ,\n" +
            "\"HeaderGTR_Mode\" integer ,\n\"HeaderAI_Unit\" integer ,\n\"HeaderAI_T1_SerialNumber\" varchar ,\n" +
            "\"HeaderAI_T1_TankSize\" integer ,\n\"HeaderAI_T1_MaxPressure\" integer ,\n" +
            "\"HeaderAI_T1_ReservePressure\" integer ,\n\"HeaderAI_T1_On\" integer ,\n" +
            "\"HeaderAI_T1_Name\" varchar ,\n\"HeaderAI_T2_SerialNumber\" varchar ,\n" +
            "\"HeaderAI_T2_TankSize\" integer ,\n\"HeaderAI_T2_MaxPressure\" integer ,\n" +
            "\"HeaderAI_T2_ReservePressure\" integer ,\n\"HeaderAI_T2_On\" integer ,\n" +
            "\"HeaderAI_T2_Name\" varchar ,\n\"HeaderAI_T3_SerialNumber\" varchar ,\n" +
            "\"HeaderAI_T3_MaxPressure\" integer ,\n\"HeaderAI_T3_ReservePressure\" integer ,\n" +
            "\"HeaderAI_T3_On\" integer ,\n\"HeaderAI_T3_Name\" varchar ,\n" +
            "\"HeaderAI_T4_SerialNumber\" varchar ,\n\"HeaderAI_T4_MaxPressure\" integer ,\n" +
            "\"HeaderAI_T4_ReservePressure\" integer ,\n\"HeaderAI_T4_On\" integer ,\n" +
            "\"HeaderAI_T4_Name\" varchar ,\n\"HeaderSideMountSwitchPressure\" integer ,\n" +
            "\"HeaderExtendedDiveSampleIndicator\" integer ,\n\"HeaderOem\" integer ,\n" +
            "\"HeaderLang\" integer ,\n\"HeaderOCRecSubMode\" integer ,\n" +
            "\"HeaderTotalStackTimeInSeconds\" integer ,\n\"HeaderRemainingStackTimeInSeconds\" integer ,\n" +
            "\"HeaderTotalOnTimeInSeconds\" integer ,\n\"HeaderDepthAlertEnabled\" integer ,\n" +
            "\"HeaderDepthAlertValue\" float ,\n\"HeaderTimeAlertEnabled\" integer ,\n" +
            "\"HeaderTimeAlertValueInMinutes\" integer ,\n\"HeaderLowNdlAlertEnabled\" integer ,\n" +
            "\"HeaderLowNdlAlertValueInMinutes\" integer ,\n\"FooterSurfaceTime\" integer ,\n" +
            "\"FooterTempUnitSystem\" integer ,\n\"InternalBatteryVoltage\" float ,\n" +
            "\"InternalBatteryVoltage_d100\" float ,\n\"FooterInternalBatteryVoltage_d100\" float ,\n" +
            "\"FooterO2Sensor1Status\" integer ,\n\"FooterO2Sensor2Status\" integer ,\n" +
            "\"FooterO2Sensor3Status\" integer ,\n\"FooterOcGas0O2Percent\" integer ,\n" +
            "\"FooterOcGas1O2Percent\" integer ,\n\"FooterOcGas2O2Percent\" integer ,\n" +
            "\"FooterOcGas3O2Percent\" integer ,\n\"FooterOcGas4O2Percent\" integer ,\n" +
            "\"FooterOcGas0HePercent\" integer ,\n\"FooterOcGas1HePercent\" integer ,\n" +
            "\"FooterOcGas2HePercent\" integer ,\n\"FooterOcGas3HePercent\" integer ,\n" +
            "\"FooterOcGas4HePercent\" integer ,\n\"FooterCcGas0O2Percent\" integer ,\n" +
            "\"FooterCcGas1O2Percent\" integer ,\n\"FooterCcGas2O2Percent\" integer ,\n" +
            "\"FooterCcGas3O2Percent\" integer ,\n\"FooterCcGas4O2Percent\" integer ,\n" +
            "\"FooterCcGas0HePercent\" integer ,\n\"FooterCcGas1HePercent\" integer ,\n" +
            "\"FooterCcGas2HePercent\" integer ,\n\"FooterCcGas3HePercent\" integer ,\n" +
            "\"FooterCcGas4HePercent\" integer ,\n\"FooterSwitchUpSetting\" integer ,\n" +
            "\"FooterSwitchUpDepth\" integer ,\n\"FooterSwitchDownSetting\" integer ,\n" +
            "\"FooterSwitchDownDepth\" integer ,\n\"FooterO2SensorMode\" integer ,\n" +
            "\"FooterSurfacePressure\" integer ,\n\"FooterErrorFlags0\" varchar ,\n" +
            "\"FooterErrorFlags1\" varchar ,\n\"FooterErrorFlags2\" varchar ,\n\"FooterErrorFlags3\" varchar ,\n" +
            "\"FooterErrorAcks0\" varchar ,\n\"FooterErrorAcks1\" varchar ,\n\"FooterErrorAcks2\" varchar ,\n" +
            "\"FooterErrorAcks3\" varchar ,\n\"FooterCurrentEventLogNumber\" integer ,\n" +
            "\"FooterSolenoidDepthCompensation\" integer ,\n\"FooterOcMinimumPPO2\" integer ,\n" +
            "\"FooterOcMaximumPPO2\" integer ,\n\"FooterOcDecoPPO2\" integer ,\n" +
            "\"FooterCcMinimumPPO2\" integer ,\n\"FooterCcMaximumPPO2\" integer ,\n" +
            "\"FooterDecoViolatedDisplayed\" integer ,\n\"FooterLastStopDepth\" integer ,\n" +
            "\"FooterEndDiveDelay\" integer ,\n\"FooterClockFormat\" integer ,\n\"FooterTitleColor\" integer ,\n" +
            "\"FooterShowOCOnlyPPO2\" integer ,\n\"FooterSalinity\" integer ,\n\"FooterGfs\" integer ,\n" +
            "\"FooterCo2TempEnabled\" integer ,\n\"FooterCo2TempWarmupFlags\" integer ,\n" +
            "\"FooterCo2TempReadyFlags\" integer ,\n\"FooterCo2TempScrubberState\" integer ,\n" +
            "\"FooterCo2TempCurrentRct\" integer ,\n\"FooterCo2TempCurrentRst\" integer ,\n" +
            "\"FooterCo2TempMinimumRct\" integer ,\n\"FooterCo2TempMinimumRctDiveMinutes\" integer ,\n" +
            "\"FooterCo2TempMinimumRst\" integer ,\n\"FooterCo2TempMinimumRstDiveMinutes\" integer ,\n" +
            "\"FooterMode\" integer ,\n\"FooterGasState\" integer ,\n\"FooterAI_Mode\" integer ,\n" +
            "\"FooterGTR_Mode\" integer ,\n\"FooterAI_Unit\" integer ,\n\"FooterAI_T1_SerialNumber\" varchar ,\n" +
            "\"FooterAI_T1_TankSize\" integer ,\n\"FooterAI_T1_MaxPressure\" integer ,\n" +
            "\"FooterAI_T1_ReservePressure\" integer ,\n\"FooterAI_T1_On\" integer ,\n" +
            "\"FooterAI_T1_Name\" varchar ,\n\"FooterAI_T2_SerialNumber\" varchar ,\n" +
            "\"FooterAI_T2_TankSize\" integer ,\n\"FooterAI_T2_MaxPressure\" integer ,\n" +
            "\"FooterAI_T2_ReservePressure\" integer ,\n\"FooterAI_T2_On\" integer ,\n" +
            "\"FooterAI_T2_Name\" varchar ,\n\"FooterAI_T3_SerialNumber\" varchar ,\n" +
            "\"FooterAI_T3_TankSize\" integer ,\n\"FooterAI_T3_MaxPressure\" integer ,\n" +
            "\"FooterAI_T3_ReservePressure\" integer ,\n\"FooterAI_T3_On\" integer ,\n" +
            "\"FooterAI_T3_Name\" varchar ,\n\"FooterAI_T4_SerialNumber\" varchar ,\n" +
            "\"FooterAI_T4_TankSize\" integer ,\n\"FooterAI_T4_MaxPressure\" integer ,\n" +
            "\"FooterAI_T4_ReservePressure\" integer ,\n\"FooterAI_T4_On\" integer ,\n" +
            "\"FooterAI_T4_Name\" varchar ,\n\"FooterSideMountSwitchPressure\" integer ,\n" +
            "\"FooterExtendedDiveSampleIndicator\" integer ,\n\"FooterAverageDiveDepth\" integer ,\n" +
            "\"FooterDiveTimeInSeconds\" integer ,\n\"LogChecksum\" integer ,\n\"FooterOem\" integer ,\n" +
            "\"FooterLang\" integer ,\n\"FooterAverageSacPSIPerMin\" float ,\n\"FooterOCRecSubMode\" integer ,\n" +
            "\"FooterTotalStackTimeInSeconds\" integer ,\n\"FooterRemainingStackTimeInSeconds\" integer ,\n" +
            "\"FooterTotalOnTimeInSeconds\" integer ,\n\"FooterDepthAlertEnabled\" integer ,\n" +
            "\"FooterDepthAlertValue\" float ,\n\"FooterTimeAlertEnabled\" integer ,\n" +
            "\"FooterTimeAlertValueInMinutes\" integer ,\n\"FooterLowNdlAlertEnabled\" integer ,\n" +
            "\"FooterLowNdlAlertValueInMinutes\" integer ,\n\"sampleRateMs\" integer )",
        "CREATE TABLE \"DeletedLogs\"(\n\"DiveID\" varchar primary key not null ,\n" +
            "\"DeletedAsOf\" datetime )",
        "CREATE TABLE \"log_data\"(\n\"log_id\" varchar primary key not null ,\n\"table_version\" bigint ,\n" +
            "\"created_unixtime\" bigint ,\n\"modified_unixtime\" bigint ,\n\"file_name\" varchar ,\n" +
            "\"format\" varchar ,\n\"format_version\" integer ,\n\"calculated_values_from_samples\" varchar ,\n" +
            "\"data_bytes_1\" blob ,\n\"data_bytes_2\" blob ,\n\"data_bytes_3\" blob ,\n\"data_bytes_4\" blob )",
        "CREATE TABLE \"SyncV3MetadataDiveDetail\"(\n\"Id\" varchar primary key not null ,\n" +
            "\"LastModifiedDevice\" varchar ,\n\"LastModifiedServerTime\" datetime ,\n" +
            "\"CreatedDevice\" varchar ,\n\"CreatedTime\" datetime ,\n\"FieldTimeStampJson\" varchar ,\n" +
            "\"Version\" integer )",
        "CREATE TABLE \"SyncV3MetadataDelete\"(\n\"Id\" varchar primary key not null ,\n" +
            "\"LastModifiedDevice\" varchar ,\n\"LastModifiedServerTime\" datetime ,\n" +
            "\"CreatedDevice\" varchar ,\n\"CreatedTime\" datetime ,\n\"Version\" integer )",
        "CREATE TABLE \"SWC_TableVersion\"(\n\"Id\" integer primary key autoincrement not null ,\n" +
            "\"DbVersion\" integer ,\n\"Updated\" integer )",
        "CREATE TABLE \"SyncV3MetadataDiveLog\"(\n\"Id\" varchar primary key not null ,\n" +
            "\"LastModifiedDevice\" varchar ,\n\"LastModifiedServerTime\" datetime ,\n" +
            "\"CreatedDevice\" varchar ,\n\"CreatedTime\" datetime )",
        "CREATE TABLE \"StoredDiveComputer\"(\n\"SerialNumber\" bigint primary key not null ,\n" +
            "\"Version\" bigint ,\n\"JsonData\" varchar )",
        "CREATE TABLE \"IdToDiveComputer\"(\n\"SerialNumber\" bigint primary key not null ,\n" +
            "\"Version\" bigint ,\n\"DiveComputerConnectionId\" varchar )",
        "CREATE TABLE \"CustomDiveComputer\"(\n\"SerialNumber\" bigint primary key not null ,\n" +
            "\"Version\" bigint ,\n\"UpdateTime\" datetime ,\n\"JsonData\" varchar )",
        "CREATE UNIQUE INDEX \"DeletedLogs_DiveID\" on \"DeletedLogs\"(\"DiveID\")",
        "CREATE INDEX dive_log_records_diveLogId ON dive_log_records(diveLogId)",
        "CREATE INDEX dive_logs_diveLogId ON dive_logs(diveId)",
        "CREATE UNIQUE INDEX \"StoredDiveComputer_SerialNumber\" on \"StoredDiveComputer\"(\"SerialNumber\")",
        "CREATE UNIQUE INDEX \"IdToDiveComputer_SerialNumber\" on \"IdToDiveComputer\"(\"SerialNumber\")",
        "CREATE UNIQUE INDEX \"CustomDiveComputer_SerialNumber\" on \"CustomDiveComputer\"(\"SerialNumber\")",
        "CREATE TABLE \"dive_site\"(\n\"Guid\" varchar primary key not null ,\n\"GeoHash\" varchar ,\n" +
            "\"Name\" varchar ,\n\"Location\" varchar ,\n\"Region\" varchar ,\n\"Country\" varchar ,\n" +
            "\"CountryCode\" varchar ,\n\"Latitude\" float ,\n\"Longitude\" float ,\n\"timestamps\" varchar ,\n" +
            "\"model_version\" integer ,\n\"created_at\" bigint ,\n\"modified_at\" bigint )",
    )

    /** Id, DbVersion, Updated. */
    val VERSIONS: List<Triple<Long, Long, Long>> = listOf(
        Triple(0, 10, 9),
        Triple(1, 1, 0),
        Triple(2, 2, 1),
        Triple(3, 3, 2),
        Triple(4, 4, 3),
        Triple(5, 5, 4),
        Triple(6, 6, 5),
        Triple(7, 7, 6),
        Triple(8, 8, 7),
        Triple(9, 9, 8),
        Triple(10, 11, 10),
        Triple(11, 12, 11),
    )
}
