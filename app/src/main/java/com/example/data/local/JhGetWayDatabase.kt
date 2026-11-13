package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class EnumConverters {
    @TypeConverter fun fromProviderType(v: ProviderType): String = v.name
    @TypeConverter fun toProviderType(v: String): ProviderType = ProviderType.valueOf(v)
    @TypeConverter fun fromApiFormat(v: ApiFormat): String = v.name
    @TypeConverter fun toApiFormat(v: String): ApiFormat = ApiFormat.valueOf(v)
    @TypeConverter fun fromProviderStatus(v: ProviderStatus): String = v.name
    @TypeConverter fun toProviderStatus(v: String): ProviderStatus = ProviderStatus.valueOf(v)
    @TypeConverter fun fromKeyStatus(v: KeyStatus): String = v.name
    @TypeConverter fun toKeyStatus(v: String): KeyStatus = KeyStatus.valueOf(v)
    @TypeConverter fun fromKeyPolicy(v: KeySelectionPolicy): String = v.name
    @TypeConverter fun toKeyPolicy(v: String): KeySelectionPolicy = KeySelectionPolicy.valueOf(v)
    @TypeConverter fun fromModelType(v: ModelType): String = v.name
    @TypeConverter fun toModelType(v: String): ModelType = ModelType.valueOf(v)
    @TypeConverter fun fromAgentType(v: CodingAgentType): String = v.name
    @TypeConverter fun toAgentType(v: String): CodingAgentType = CodingAgentType.valueOf(v)
    @TypeConverter fun fromLocalEngineType(v: LocalEngineType): String = v.name
    @TypeConverter fun toLocalEngineType(v: String): LocalEngineType = LocalEngineType.valueOf(v)
    @TypeConverter fun fromMcpTransportType(v: McpTransportType): String = v.name
    @TypeConverter fun toMcpTransportType(v: String): McpTransportType = McpTransportType.valueOf(v)
    @TypeConverter fun fromToolPermission(v: ToolPermission): String = v.name
    @TypeConverter fun toToolPermission(v: String): ToolPermission = ToolPermission.valueOf(v)
    @TypeConverter fun fromDeviceConnType(v: DeviceConnectionType): String = v.name
    @TypeConverter fun toDeviceConnType(v: String): DeviceConnectionType = DeviceConnectionType.valueOf(v)
    @TypeConverter fun fromActivityCategory(v: ActivityCategory): String = v.name
    @TypeConverter fun toActivityCategory(v: String): ActivityCategory = ActivityCategory.valueOf(v)
}

@Database(
    entities = [
        ProviderEntity::class,
        ApiKeyEntity::class,
        ModelEntity::class,
        RoutingRuleEntity::class,
        CodingAgentEntity::class,
        LocalAiServerEntity::class,
        McpServerEntity::class,
        McpToolEntity::class,
        WorkflowEntity::class,
        WorkflowRunEntity::class,
        DeviceEntity::class,
        RequestMetricEntity::class,
        ActivityLogEntity::class,
        PlaygroundSessionEntity::class,
        PlaygroundMessageEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(EnumConverters::class)
abstract class JhGetWayDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun apiKeyDao(): ApiKeyDao
    abstract fun modelDao(): ModelDao
    abstract fun routingRuleDao(): RoutingRuleDao
    abstract fun codingAgentDao(): CodingAgentDao
    abstract fun localAiServerDao(): LocalAiServerDao
    abstract fun mcpDao(): McpDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun deviceDao(): DeviceDao
    abstract fun metricAndLogDao(): MetricAndLogDao
    abstract fun playgroundDao(): PlaygroundDao

    companion object {
        @Volatile private var INSTANCE: JhGetWayDatabase? = null

        fun getInstance(context: Context): JhGetWayDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JhGetWayDatabase::class.java,
                    "jh_getway_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
