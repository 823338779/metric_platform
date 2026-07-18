import { Button, Descriptions, Drawer, List, Space, Tag } from 'antd';
import type { DatasetRelationship } from '../../api/types';
export function RelationshipDrawer({
  value,
  open,
  onClose,
  onRevalidate,
  onDelete,
}: {
  value?: DatasetRelationship;
  open: boolean;
  onClose: () => void;
  onRevalidate?: (id: string) => void;
  onDelete?: (id: string) => void;
}) {
  return (
    <Drawer width={520} open={open} onClose={onClose} title={value?.name ?? '数据集关系'}>
      {value && (
        <>
          <Descriptions
            column={1}
            items={[
              { key: 'c', label: '基数', children: value.cardinality },
              { key: 'j', label: '连接方式', children: 'LEFT JOIN（固定）' },
              {
                key: 's',
                label: '状态',
                children: (
                  <Tag color={value.validationStatus === 'VALID' ? 'green' : 'red'}>
                    {value.validationStatus}
                  </Tag>
                ),
              },
            ]}
          />
          <List
            header="复合关联键"
            dataSource={value.fieldPairs}
            renderItem={(p, i) => (
              <List.Item>
                键 {i + 1}: {p.sourceFieldId} → {p.targetFieldId}
              </List.Item>
            )}
          />
          <Space>
            <Button onClick={() => onRevalidate?.(value.id)}>重新校验</Button>
            <Button danger onClick={() => onDelete?.(value.id)}>
              删除关系
            </Button>
          </Space>
        </>
      )}
    </Drawer>
  );
}
