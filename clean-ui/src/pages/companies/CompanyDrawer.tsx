import { useState } from 'react';
import { Button, Collapse, Drawer, List, Space, Spin, Table, Tabs, Tag, Typography } from 'antd';
import {
  useCompany,
  useCompanyContacts,
  useCompanyVessels,
  useCompanyMutations,
  usePeople,
} from '../../api/hooks';
import ConfirmTag from '../../components/ConfirmTag';
import ContactLine from '../../components/ContactLine';
import GreetingName from '../../components/GreetingName';
import VesselDrawer from '../vessels/VesselDrawer';
import VesselForm from '../vessels/VesselForm';
import type { CompanyResponse, ContactResponse, VesselResponse } from '../../api/types';

interface Props {
  companyId?: number;
  onClose: () => void;
  onEdit: (c: CompanyResponse) => void;
}

export default function CompanyDrawer({ companyId, onClose, onEdit }: Props) {
  const { data, isLoading } = useCompany(companyId);
  const { confirm, remove } = useCompanyMutations();
  const c = data?.company;

  const [vesselId, setVesselId] = useState<number>();
  const [vesselFormOpen, setVesselFormOpen] = useState(false);
  const [editingVessel, setEditingVessel] = useState<VesselResponse | null>(null);

  return (
    <Drawer
      open={companyId != null}
      width={620}
      title={c?.name ?? 'Company'}
      onClose={onClose}
      extra={
        c && (
          <Space>
            <Button onClick={() => onEdit(c)}>Edit</Button>
            <Button danger loading={remove.isPending} onClick={() => remove.mutate(c.id, { onSuccess: onClose })}>
              Delete
            </Button>
          </Space>
        )
      }
    >
      {isLoading || !c ? (
        <Spin />
      ) : (
        <>
          <Space style={{ marginBottom: 12 }} wrap>
            <ConfirmTag
              confirmed={c.confirmed}
              confirmedAt={c.confirmedAt}
              confirmedBy={c.confirmedBy}
              loading={confirm.isPending}
              onConfirm={(body) => confirm.mutate({ id: c.id, confirmed: true, body })}
              onUnconfirm={() => confirm.mutate({ id: c.id, confirmed: false })}
            />
            {c.cityName && <Tag>{c.cityName}</Tag>}
            {c.shipowner && <Tag color="blue">owner</Tag>}
            {c.charterer && <Tag color="green">charterer</Tag>}
            {c.broker && <Tag color="gold">broker</Tag>}
            {c.agent && <Tag color="purple">agent</Tag>}
          </Space>
          <Tabs
            items={[
              {
                key: 'vessels',
                label: 'Vessels',
                children: <CompanyVesselsTab id={c.id} onOpenVessel={setVesselId} />,
              },
              { key: 'people', label: 'People', children: <CompanyPeopleTab id={c.id} /> },
              { key: 'contacts', label: 'Contacts', children: <CompanyContactsTab id={c.id} /> },
            ]}
          />

          <VesselDrawer
            vesselId={vesselId}
            onClose={() => setVesselId(undefined)}
            onEdit={(v) => { setEditingVessel(v); setVesselFormOpen(true); }}
          />
          <VesselForm
            open={vesselFormOpen}
            editing={editingVessel}
            onClose={() => setVesselFormOpen(false)}
          />
        </>
      )}
    </Drawer>
  );
}

function CompanyVesselsTab({ id, onOpenVessel }: { id: number; onOpenVessel: (vesselId: number) => void }) {
  const { data, isLoading } = useCompanyVessels(id);
  return (
    <Table
      rowKey="id"
      size="small"
      loading={isLoading}
      dataSource={data ?? []}
      pagination={false}
      columns={[
        {
          title: 'Name',
          dataIndex: 'name',
          render: (name: string, v) => (
            <Typography.Link onClick={() => onOpenVessel(v.id)}>{name}</Typography.Link>
          ),
        },
        { title: 'DWT', dataIndex: 'deadweightTonnage' },
        { title: 'Year', dataIndex: 'yearBuilt' },
        { title: 'Type', dataIndex: 'vesselType' },
      ]}
    />
  );
}

function CompanyContactsTab({ id }: { id: number }) {
  const { data, isLoading } = useCompanyContacts(id);
  return (
    <List
      size="small"
      loading={isLoading}
      dataSource={data ?? []}
      locale={{ emptyText: 'No contacts' }}
      renderItem={(ct) => <ContactLine ct={ct} />}
    />
  );
}

/** People at the company: name + copiable greeting shown compactly, click to expand contacts. */
function CompanyPeopleTab({ id }: { id: number }) {
  const { data: people, isLoading: loadingPeople } = usePeople(id);
  const { data: contacts, isLoading: loadingContacts } = useCompanyContacts(id);

  if (loadingPeople || loadingContacts) return <Spin />;
  if (!people || people.length === 0) return <Typography.Text type="secondary">No people.</Typography.Text>;

  const byPerson = new Map<number, ContactResponse[]>();
  (contacts ?? []).forEach((ct) => {
    if (ct.personId == null) return;
    const list = byPerson.get(ct.personId) ?? [];
    list.push(ct);
    byPerson.set(ct.personId, list);
  });

  return (
    <Collapse
      accordion
      items={people.map((p) => {
        const personContacts = byPerson.get(p.id) ?? [];
        return {
          key: String(p.id),
          label: (
            <Space wrap>
              <strong>{p.fullName}</strong>
              <GreetingName title={p.title} name={p.greetingName} type="success" />
              <Typography.Text type="secondary">
                {personContacts.length} contact{personContacts.length === 1 ? '' : 's'}
              </Typography.Text>
            </Space>
          ),
          children: personContacts.length ? (
            <List
              size="small"
              dataSource={personContacts}
              renderItem={(ct) => <ContactLine ct={ct} showGreeting={false} />}
            />
          ) : (
            <Typography.Text type="secondary">No contacts</Typography.Text>
          ),
        };
      })}
    />
  );
}
