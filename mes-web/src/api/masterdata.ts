import { http } from './http'

/** 主数据接口：GET /api/workcenters（经 Gateway 转发到 masterdata-service，需 masterdata:read）。 */
export interface Station {
  code: string
  name: string
  workCenterCode: string
}

export interface WorkCenter {
  code: string
  name: string
  workshop: string
  outsourced: boolean
  stations: Station[]
}

/** 工作中心列表（含工位）。 */
export async function listWorkCenters(): Promise<WorkCenter[]> {
  return (await http.get<{ data: WorkCenter[] }>('/workcenters')).data.data
}

/** 展开为工位列表；可按工作中心过滤，供改派下拉使用。 */
export async function listStations(workCenterCode?: string): Promise<Station[]> {
  const centers = await listWorkCenters()
  return centers.filter((c) => !workCenterCode || c.code === workCenterCode).flatMap((c) => c.stations ?? [])
}
