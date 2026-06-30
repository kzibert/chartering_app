import { useQuery } from '@tanstack/react-query';
import { Card, Col, List, Row, Statistic, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { vesselsApi } from '../api/vessels';
import { companiesApi } from '../api/companies';
import { contactsApi } from '../api/contacts';
import { useContactMutations, useVesselMutations, useCompanyMutations } from '../api/hooks';
import ConfirmTag from '../components/ConfirmTag';

const PREVIEW = { size: 5, confirmed: false } as const;

export default function Dashboard() {
  const navigate = useNavigate();

  const counts = useQuery({
    queryKey: ['dashboard', 'counts'],
    queryFn: async () => {
      const [v, c, ct] = await Promise.all([
        vesselsApi.search({ size: 1 }),
        companiesApi.search({ size: 1 }),
        contactsApi.search({ size: 1 }),
      ]);
      return { vessels: v.totalElements, companies: c.totalElements, contacts: ct.totalElements };
    },
  });

  const vesselsToDo = useQuery({
    queryKey: ['dashboard', 'vessels-todo'],
    queryFn: () => vesselsApi.search(PREVIEW),
  });
  const companiesToDo = useQuery({
    queryKey: ['dashboard', 'companies-todo'],
    queryFn: () => companiesApi.search(PREVIEW),
  });
  const contactsToDo = useQuery({
    queryKey: ['dashboard', 'contacts-todo'],
    queryFn: () => contactsApi.search(PREVIEW),
  });

  const vesselM = useVesselMutations();
  const companyM = useCompanyMutations();
  const contactM = useContactMutations();

  return (
    <>
      <Row gutter={16}>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="Vessels"
              value={counts.data?.vessels ?? 0}
              loading={counts.isLoading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="Companies"
              value={counts.data?.companies ?? 0}
              loading={counts.isLoading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="Contacts"
              value={counts.data?.contacts ?? 0}
              loading={counts.isLoading}
            />
          </Card>
        </Col>
      </Row>

      <Typography.Title level={4} style={{ marginTop: 24 }}>
        Reconnect worklist — needs confirming
      </Typography.Title>
      <Row gutter={16}>
        <Col xs={24} lg={8}>
          <Card
            title={`Vessels (${vesselsToDo.data?.totalElements ?? 0})`}
            extra={<a onClick={() => navigate('/vessels')}>open</a>}
          >
            <List
              loading={vesselsToDo.isLoading}
              dataSource={vesselsToDo.data?.content ?? []}
              renderItem={(v) => (
                <List.Item
                  actions={[
                    <ConfirmTag
                      key="c"
                      confirmed={v.confirmed}
                      loading={vesselM.confirm.isPending}
                      onConfirm={(body) => vesselM.confirm.mutate({ id: v.id, confirmed: true, body })}
                      onUnconfirm={() => vesselM.confirm.mutate({ id: v.id, confirmed: false })}
                    />,
                  ]}
                >
                  <List.Item.Meta title={v.name} description={v.ownerName ?? '—'} />
                </List.Item>
              )}
            />
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card
            title={`Companies (${companiesToDo.data?.totalElements ?? 0})`}
            extra={<a onClick={() => navigate('/companies')}>open</a>}
          >
            <List
              loading={companiesToDo.isLoading}
              dataSource={companiesToDo.data?.content ?? []}
              renderItem={(c) => (
                <List.Item
                  actions={[
                    <ConfirmTag
                      key="c"
                      confirmed={c.confirmed}
                      loading={companyM.confirm.isPending}
                      onConfirm={(body) => companyM.confirm.mutate({ id: c.id, confirmed: true, body })}
                      onUnconfirm={() => companyM.confirm.mutate({ id: c.id, confirmed: false })}
                    />,
                  ]}
                >
                  <List.Item.Meta title={c.name} description={c.cityName ?? '—'} />
                </List.Item>
              )}
            />
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card
            title={`Contacts (${contactsToDo.data?.totalElements ?? 0})`}
            extra={<a onClick={() => navigate('/contacts')}>open</a>}
          >
            <List
              loading={contactsToDo.isLoading}
              dataSource={contactsToDo.data?.content ?? []}
              renderItem={(ct) => (
                <List.Item
                  actions={[
                    <ConfirmTag
                      key="c"
                      confirmed={ct.confirmed}
                      loading={contactM.confirm.isPending}
                      onConfirm={(body) => contactM.confirm.mutate({ id: ct.id, confirmed: true, body })}
                      onUnconfirm={() => contactM.confirm.mutate({ id: ct.id, confirmed: false })}
                    />,
                  ]}
                >
                  <List.Item.Meta title={ct.contactValue} description={ct.contactKind} />
                </List.Item>
              )}
            />
          </Card>
        </Col>
      </Row>
    </>
  );
}
